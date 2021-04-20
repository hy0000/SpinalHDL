
package spinal.tester.scalatest

import org.scalatest.FunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.bmb.sim.{BmbDriver, BmbMemoryAgent}
import spinal.lib.bus.bmb.{BmbAccessParameter, BmbParameter}
import spinal.lib.com.usb._
import spinal.lib.com.usb.ohci._
import spinal.lib.com.usb.phy.{UsbHubLsFs, UsbLsFsPhy}
import spinal.lib.eda.bench.{AlteraStdTargets, Bench, Rtl, XilinxStdTargets}
import spinal.lib.sim._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class SpinalSimUsbHostTester extends FunSuite{

  /*test("host")*/{
    val p = UsbOhciParameter(
      noPowerSwitching = true,
      powerSwitchingMode = true,
      noOverCurrentProtection = true,
      powerOnToPowerGoodTime = 10,
      fsRatio = 4,
      dataWidth = 32,
      portsConfig = List.fill(4)(OhciPortParameter())
    )

    SimConfig.withFstWave.compile(new UsbOhciTbTop(p)).doSim(seed = 42){dut =>
      val utils = new TesterUtils(dut)
      import utils._



      val m = memory.memory

      var activity = true

      dut.clockDomain.forkSimSpeedPrinter()
      dut.clockDomain.waitSampling(2)
      forkSimSporadicWave(
        captures = Seq(
          3e-3 -> 6e-3
        )
      )


      val hcca = HCCA(malloc)
      hcca.save(ram)
      ctrl.write(hcca.address, hcHCCA)

      ctrl.write(BLF | CLF, hcCommand)
      dut.clockDomain.waitSampling(100)
      ctrl.write(USB_OPERATIONAL | BLE | CLE | PLE | 0x3, hcControl)

      dut.clockDomain.waitSampling(100)

      val doneChecks = mutable.HashMap[Int, TD => Unit]()
      //Interrupt handler
      fork{
        ctrl.write(UsbOhci.MasterInterruptEnable | UsbOhci.WritebackDoneHead, UsbOhci.HcInterruptEnable)
        while(true) {
          dut.clockDomain.waitSamplingWhere(dut.irq.toBoolean)
          val flags = ctrl.read(UsbOhci.HcInterruptStatus)
          ctrl.write(0xFFFFFFFFl, UsbOhci.HcInterruptStatus)
          if((flags & UsbOhci.WritebackDoneHead) != 0){
            var ptr = memory.memory.readInt(hcca.address + 0x84) & ~1
            assert(ptr != 0)
            while(ptr != 0){
              val td = TD(ptr).load(memory.memory)
              doneChecks.remove(ptr).get.apply(td)
              ptr = td.nextTD
            }
          }
        }
      }

      val p0 = fork {
        devices(0).connect(lowSpeed = false)
        waitConnected(0)
        setPortReset(0)
        waitPortReset(0)
      }

      val p1 = fork {
        devices(1).connect(lowSpeed = true)
        waitConnected(1)
        setPortReset(1)
        waitPortReset(1)
      }
      p0.join()
      p1.join()

      def deviceDelayed(ls : Boolean)(body : => Unit){
        delayed(4*83333*(if(ls) 8 else 1)) {body} //TODO improve
      }



//      var fmNumberOld = ctrl.read(UsbOhci.HcFmNumber)
//      fork{
//        val fmNumber = ctrl.read(UsbOhci.HcFmNumber)
//        if(fmNumber == fmNumberOld + 1){
//
//        } else {
//          assert(fmNumber == fmNumberOld)
//        }
//      }

      def addTd(td : TD, ed: ED): Unit ={
        if(ed.tailP == ed.headP){
          ed.headP = td.address
        } else {
          val last = TD(ed.tailP).load(m)
          last.nextTD = td.address
        }
        ed.tailP = td.address
      }

      def initED(ed : ED): Unit ={
        val td = TD(malloc)
        ed.tailP = td.address
        ed.headP = td.address
      }

      def newTd(ed: ED) : TD = {
        ed.load(m)
        val td = TD(ed.tailP)
        val dummy = TD(malloc)
        ed.tailP = dummy.address
        td.nextTD = dummy.address
        ed.save(m)
        td
      }

      val bulksEd, controlsEd = ArrayBuffer[ED]()

      def addBulkEd(ed : ED): Unit = {
        if(bulksEd.isEmpty){
          ctrl.write(ed.address, hcBulkHeadED)
        } else {
          val edLast = bulksEd.last.load(m)
          edLast.nextED = ed.address
          edLast.save(m)
        }
        bulksEd += ed
      }

      val ed0 = ED(malloc)
      ed0.F = false
      ed0.D = 0
      ed0.FA = 42
      ed0.EN = 6
      ed0.MPS = 64
      initED(ed0)
      addBulkEd(ed0)
      ed0.save(ram)

      var totalBytes = 0
      for(tdId <- 0 until 100) { //XXX
        var size = if(Random.nextDouble() < 0.1){
          Random.nextInt(8192+1)
        } else if(Random.nextDouble() < 0.05){
          0
        }  else if(Random.nextDouble() < 0.05){
          8192
        } else {
          Random.nextInt(256+1)
        }
//        size = 32 //XXX
        totalBytes += size

        var p0, p1, p0Offset = 0
        var p0Used, p1Used = 0
        var success = false
        while(!success){
          success = true
          p0 = Random.nextInt(128*1024)*4096
          p1 = Random.nextInt(128*1024)*4096
          p0Offset = Random.nextInt((8192-size+1).min(4096))
//          p0Offset = 0 //XXX
          p0Used = (4096-p0Offset).min(size)
          p1Used = size - p0Used
          if(malloc.isAllocated(p0 + p0Offset, p0Used)) success = false
          if(p1Used != 0 && malloc.isAllocated(p1, p1Used)) success = false
        }
        totalBytes += size
        def byteToAddress(i : Int) = if (i < p0Used) p0 + p0Offset + i else p1 + i - p0Used

        if(p0Used != 0) malloc.allocateOn(p0 + p0Offset, p0Used)
        if(p1Used != 0) malloc.allocateOn(p1, p1Used)

        val td0 = newTd(ed0)
        td0.DP = Random.nextInt(3)
        td0.DP = UsbOhci.DP.IN //XXX
        td0.DI = 5
        td0.T = 2
        td0.R = Random.nextBoolean()
        td0.currentBuffer = if(size == 0) 0 else p0 + p0Offset
        td0.bufferEnd = if(p1Used != 0) p1 + p1Used - 1 else p0 + p0Offset + p0Used - 1
        td0.CC == UsbOhci.ConditionCode.notAccessed
        td0.save(ram)

        var doOverflow = td0.DP == UsbOhci.DP.IN && Random.nextDouble() < 0.05
        var doUnderflow = td0.DP == UsbOhci.DP.IN && !doOverflow && size != 0 && Random.nextDouble() < 0.05
        doOverflow = false //XXX
        doUnderflow = size != 0 //XXX

//        val refData = (0 until size) //XXX
        val refData = Array.fill(size)(Random.nextInt(256))
        if(td0.DP != UsbOhci.DP.IN) for (i <- 0 until size) {
          val address = byteToAddress(i)
          ram.write(address, refData(i).toByte)
        }

        var groups : Seq[(Seq[Int], Int)] = (0 until size).grouped(ed0.MPS).zipWithIndex.toList
        if(groups.isEmpty) groups = Seq(Seq[Int]() -> 0)
        val overflowAt = if(!doOverflow) Int.MaxValue else groups.randomPick()._2
        val underflowAt = if(!doUnderflow) Int.MaxValue else groups.randomPick()._2
        val groupLastId = List(groups.last._2, overflowAt, underflowAt).min

        for ((group, groupId) <- groups; if groupId <= groupLastId) {
          td0.DP match {
            case UsbOhci.DP.SETUP | UsbOhci.DP.OUT =>{
              val push = if(td0.DP == UsbOhci.DP.SETUP) scoreboards(0).pushSetup _ else scoreboards(0).pushOut _
              push(TockenKey(ed0.FA, ed0.EN), DataPacket(if (groupId % 2 == 0) DATA0 else DATA1, group.map(byteId => refData(byteId)))) {
                portAgents(0).emitBytes(HANDSHAKE_ACK, List(), false, true)
                activity = true
                if (groupId == groupLastId) {
                  doneChecks(td0.address) = { td =>
                    assert(td.currentBuffer == 0)
                    assert(td.CC == UsbOhci.ConditionCode.noError)
                    if(p0Used != 0) malloc.free(p0 + p0Offset)
                    if(p1Used != 0) malloc.free(p1)
                    malloc.free(td0.address)
                  }
                }
              }
            }
            case UsbOhci.DP.IN => {
              val push =  scoreboards(0).pushIn _
              push(TockenKey(ed0.FA, ed0.EN)){
                activity = true
                deviceDelayed(ls=false) {
                  var finalTransferSize = group.size
                  if(groupId == overflowAt){
                    portAgents(0).emitBytes(HANDSHAKE_ACK, group.map(refData) ++ List.fill(Random.nextInt(4)+1)(Random.nextInt(256)), true, false)
                  } else if(groupId == underflowAt){
                    finalTransferSize = Random.nextInt(group.size)
                    portAgents(0).emitBytes(HANDSHAKE_ACK, group.map(refData).take(finalTransferSize), true, false)
                  } else {
                    portAgents(0).emitBytes(HANDSHAKE_ACK, group.map(refData), true, false)
                  }
                  if (groupId == groupLastId) {
//                    println("=> " + group.map(refData).map(e => f"$e%02x").mkString(","))
                    doneChecks(td0.address) = {td =>
                      def checkDataUntil(up : Int): Unit ={
                        for (i <- 0 until up) {
                          val address = byteToAddress(i)
                          assert(m.read(address) == refData(i).toByte, f"[$i] => ${m.read(address)}%x != ${refData(i).toByte}%x")
                        }
                      }

                      ed0.load(m)
                      if(doOverflow){
                        assert(td.CC == UsbOhci.ConditionCode.bufferOverrun)
                        if(size != 0) {
                          assert(td.currentBuffer == byteToAddress(group.head))
                          checkDataUntil(group.last+1)
                        } else {
                          assert(td.currentBuffer == 0)
                        }
                      } else if(doUnderflow && !td.R){
                        assert(td.CC == UsbOhci.ConditionCode.bufferUnderrun)
                        assert(td.currentBuffer == byteToAddress(group.head))
                        checkDataUntil(group.head + finalTransferSize)
                      } else {
                        assert(td.CC == UsbOhci.ConditionCode.noError)
                        assert(td.currentBuffer == 0)
                        if(size != 0) checkDataUntil(group.head + finalTransferSize)
                      }

                      if(td.CC != UsbOhci.ConditionCode.noError){
                        assert(ed0.H)
                        fork{
                          sleep(Random.nextInt(5)*1e9)
                          ed0.H = false
                          ed0.save(m)
                          setBulkListFilled()
                        }
                      }

                      if(p0Used != 0) malloc.free(p0 + p0Offset)
                      if(p1Used != 0) malloc.free(p1)
                      println("DONNNNE")
                    }
                  }
                }
                true
              }
            }

          }

        }
        setBulkListFilled()
      }






      while(activity){
        activity = false
        sleep(10e-3*1e12)
      }

      scoreboards.foreach(_.assertEmpty)
      assert(ctrl.read(UsbOhci.HcDoneHead) == 0)
      assert(doneChecks.isEmpty)
      println(totalBytes)
    }
  }
}



object UsbHostSynthesisTest {
  def main(args: Array[String]) {
    val rawrrr = new Rtl {
      override def getName(): String = "UsbOhci"
      override def getRtlPath(): String = "UsbOhci.v"
      SpinalVerilog({
        val p = UsbOhciParameter(
          noPowerSwitching = false,
          powerSwitchingMode = false,
          noOverCurrentProtection = false,
          powerOnToPowerGoodTime = 10,
          fsRatio = 4,
          dataWidth = 32,
          portsConfig = List.fill(1)(OhciPortParameter())
        )
        UsbOhci(p, BmbParameter(
          addressWidth = 12,
          dataWidth = 32,
          sourceWidth = 0,
          contextWidth = 0,
          lengthWidth = 2
        ))
      })
    }


    val rtls = List(rawrrr)

    val targets = XilinxStdTargets().take(1)

    Bench(rtls, targets)
  }
}


