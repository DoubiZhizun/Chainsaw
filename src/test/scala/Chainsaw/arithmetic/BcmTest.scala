package Chainsaw.arithmetic

import Chainsaw._
import Chainsaw.testConfigurations._

import scala.util.Random

class BcmTest extends ChainsawFlatSpec {

  val multTypes = Seq(FullMultiplier, MsbMultiplier, LsbMultiplier)
  val useCsds = Seq(false, true)
  multTypes.foreach(multType => useCsds.foreach { useCsd =>
    val widthIn = 16
    val constantCount = 5
    val constants = Seq.fill(constantCount)(BigInt(widthIn, Random))
    constants.foreach { constant =>
      val gen = multType match {
        case FullMultiplier => Bcm(constant, FullMultiplier, widthIn, widthIn + constant.bitLength, widthIn + constant.bitLength, useCsd)
        case MsbMultiplier => Bcm(constant, MsbMultiplier, widthIn, widthIn + 2, widthIn, useCsd)
        case LsbMultiplier => Bcm(constant, LsbMultiplier, widthIn, widthIn, widthIn, useCsd)
      }
      testGenerator(gen, bcmSynth, bcmImpl)
    }
  })
}
