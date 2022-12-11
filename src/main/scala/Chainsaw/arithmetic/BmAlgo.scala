package Chainsaw.arithmetic

import Chainsaw._
import Chainsaw.xilinx.VivadoUtilEstimation

import scala.util.Random

/** long multiplication implemented by divide-and-conquer
 */
case class BmAlgo(bmSolution: BmSolution) extends HardAlgo {

  // more accurate concept for clbCost

  // TODO: latency estimation
  // TODO: apply cmults by DSP limit

  val isConstantMult = bmSolution.constant.isDefined

  /** --------
   * cost statistics
   * -------- */
  var multCost = 0
  var fixCost = 0
  var splitCost = 0
  var mergeCost = 0
  var cmultCost = 0

  //  def clearCost(): Unit = {
  //    multCost = 0
  //    splitCost = 0
  //    mergeCost = 0
  //    cmultCost = 0
  //  }

  val weightMax =
    if (bmSolution.multiplierType == LsbMultiplier) bmSolution.widthFull
    else bmSolution.widthFull * 2

  val dspLatency = 3
  val andLatency = 1

  /** --------
   * operations in BmAlgo
   * -------- */
  def splitN(x: WeightedValue, n: Int): Seq[WeightedValue] = {
    val paddedWidth = x.arithInfo.width.nextMultipleOf(n)
    val segmentWidth = x.arithInfo.width.divideAndCeil(n)
    val values = x.value.toBitValue(paddedWidth).splitN(n)
    (0 until n).map(i =>
      WeightedValue(value = values(i),
        arithInfo = ArithInfo(width = segmentWidth, weight = x.arithInfo.weight + i * segmentWidth, isPositive = x.arithInfo.isPositive, time = x.arithInfo.time)))
  }

  def splitMSB(x: WeightedValue) = {
    val (msb, main) = x.value.toBitValue(x.arithInfo.width).splitAt(x.arithInfo.width - 1)
    val msbValue = WeightedValue(value = msb,
      arithInfo = ArithInfo(width = 1, weight = x.arithInfo.weight + x.arithInfo.width - 1, time = x.arithInfo.time))
    val mainValue = WeightedValue(value = main,
      arithInfo = ArithInfo(width = x.arithInfo.width - 1, weight = x.arithInfo.weight, time = x.arithInfo.time))
    (msbValue, mainValue)
  }

  def add(v0: WeightedValue, v1: WeightedValue, constant: Boolean = false) = {
    require(v0.arithInfo.width == v1.arithInfo.width, s"v0: ${v0.arithInfo}, v1: ${v1.arithInfo}")
    if (!constant) splitCost += v0.arithInfo.width
    val width = v0.arithInfo.width + 1
    val weight = v0.arithInfo.weight // this should be reset after
    val time = v0.arithInfo.time + v0.arithInfo.width.divideAndCeil(cpaWidthMax)
    WeightedValue(value = v0.value + v1.value, arithInfo = ArithInfo(width, weight, v0.arithInfo.isPositive, time))
  }

  def mult(v0: WeightedValue, v1: WeightedValue) = {
    require(v0.arithInfo.isPositive == v1.arithInfo.isPositive)
    require(v0.arithInfo.time == v1.arithInfo.time)
    val width = v0.arithInfo.width + v1.arithInfo.width
    val weight = v0.arithInfo.weight + v1.arithInfo.weight
    val time = v0.arithInfo.time + dspLatency

    val constantWeight = if (isConstantMult) Csd(v1.value).weight else v1.arithInfo.width
    val useCMult = (isConstantMult && constantWeight < bmSolution.threshold)
    if (useCMult) {
      cmultCost += bmSolution.dspSize._1 * (constantWeight - 1) // TODO: more accurate estimation
    } else {
      multCost += bmSolution.baseMultiplier.dspCost
      fixCost += bmSolution.baseMultiplier.clbCost.toInt
    }

    WeightedValue(value = v0.value * v1.value, arithInfo = ArithInfo(width, weight, v0.arithInfo.isPositive, time))
  }

  def and(v0: WeightedValue, v1: WeightedValue) = {
    require(v1.arithInfo.width == 1)
    val width = v0.arithInfo.width
    val weight = v0.arithInfo.weight + v1.arithInfo.weight
    val time = v0.arithInfo.time + andLatency
    WeightedValue(value = v0.value * v1.value,
      arithInfo = ArithInfo(width = width, weight = weight, time = time))
  }

  def merge(weightedValues: Seq[WeightedValue], widthOut: Int): WeightedValue = {
    val base = weightedValues.map(_.arithInfo.weight).min
    mergeCost += weightedValues.map(_.arithInfo.width).sum - widthOut
    val value = weightedValues.map(_.eval).sum >> base
    WeightedValue(value = value,
      arithInfo = ArithInfo(widthOut, base, isPositive = true, weightedValues.map(_.arithInfo.time).max))
  }

  /** --------
   * algorithm
   * -------- */
  def doRectangular(x: WeightedValue, y: WeightedValue, bmSolution: BmSolution): WeightedValue = {

    if (bmSolution.isEmpty) {
      if (x.arithInfo.weight + y.arithInfo.weight < weightMax) mult(x, y) else WeightedValue(0, ArithInfo(0, x.arithInfo.weight + y.arithInfo.weight))
    }
    else {

      val current = bmSolution.topDecomposition
      import current._

      val aWords = splitN(x, aSplit) // width = baseHeight
      val bWords = splitN(y, bSplit) // width = baseWidth

      def doNSplit(aWords: Seq[WeightedValue], bWords: Seq[WeightedValue]): Seq[WeightedValue] = {

        multiplierType match {
          case FullMultiplier =>
            if (isKara) {
              val diagonals: Seq[WeightedValue] = (0 until split).map { i =>
                doRectangular(aWords(i), bWords(i), bmSolution.subSolution(bmSolution.multiplierType))
              }

              val prods: Seq[WeightedValue] = Seq.tabulate(split, split) { (i, j) =>
                if (i > j) { // upper triangular, generated by karatsuba method
                  // pre-addition
                  val weight = aWords(i).arithInfo.weight + bWords(j).arithInfo.weight
                  require(aWords(i).arithInfo.weight + bWords(j).arithInfo.weight == aWords(j).arithInfo.weight + bWords(i).arithInfo.weight)
                  val combinedA = add(aWords(i), aWords(j))
                  val combinedB = add(bWords(i), bWords(j), isConstantMult)
                  val (aMsb, aMain) = splitMSB(combinedA)
                  val (bMsb, bMain) = splitMSB(combinedB)
                  // sub-multiplication
                  val full = doRectangular(aMain, bMain, bmSolution.subSolution(FullMultiplier)).withWeight(weight)
                  val high = -diagonals(i).withWeight(weight)
                  val low = -diagonals(j).withWeight(weight)
                  // full - high - low
                  val mainSegments = Seq(full, high, low)
                  // side-multiplications
                  val sideA = and(bMain, aMsb).withWeight(weight + baseHeight)
                  val sideB = and(aMain, bMsb).withWeight(weight + baseWidth)
                  val ab = and(aMsb, bMsb).withWeight(weight + baseWidth + baseHeight)
                  val sideSegments = Seq(sideA, sideB, ab)
                  Some(mainSegments ++ sideSegments)
                } else None
              }.flatten.flatten.flatten
              diagonals ++ prods
            } else {
              Seq.tabulate(split, split) { (i, j) =>
                doRectangular(aWords(i), bWords(j), bmSolution.subSolution(bmSolution.multiplierType))
              }.flatten
            }

          case SquareMultiplier =>
            Seq.tabulate(split, split) { (i, j) =>
              if (i >= j) { // upper triangular
                val prod = doRectangular(aWords(i), bWords(j), bmSolution.subSolution(FullMultiplier))
                val ret = if (i != j) prod << 1 else prod
                Some(ret)
              } else None
            }.flatten.flatten

          case MsbMultiplier =>
            Seq.tabulate(split, split) { (i, j) =>
              if (i + j >= split - 1) {
                val multType = if (i + j == split - 1) bmSolution.multiplierType else FullMultiplier
                val ret = doRectangular(aWords(i), bWords(j), bmSolution.subSolution(multType))
                Some(ret)
              }
              else None
            }.flatten.flatten

          case LsbMultiplier =>
            Seq.tabulate(split, split) { (i, j) =>
              if (i + j <= split - 1) {
                val multType = if (i + j == split - 1) bmSolution.multiplierType else FullMultiplier
                val ret = doRectangular(aWords(i), bWords(j), bmSolution.subSolution(multType))
                Some(ret)
              }
              else None
            }.flatten.flatten
        }
      }

      val segments = Seq.tabulate(factorB, factorA) { (i, j) => // for rectangular
        // distribute words to N-split sub modules
        val as = aWords.zipWithIndex.filter(_._2 % factorB == i).map(_._1)
        val bs = bWords.zipWithIndex.filter(_._2 % factorA == j).map(_._1)
        doNSplit(as, bs)
      }.flatten.flatten

      val validSegments = segments.filter(_.arithInfo.weight < weightMax)
      assert(validSegments.forall(_.arithInfo.width != 0))
      val ret = merge(validSegments, widthOut)
      ret
    }
  }

  /** --------
   * entrance to algorithm
   * -------- */
  def impl(x: BigInt, y: BigInt, verbose: Boolean = false): BigInt = {

    def adjustForMultType(value: BigInt) = {
      bmSolution.multiplierType match {
        case MsbMultiplier => value >> bmSolution.widthFull
        case LsbMultiplier => value.mod(Pow2(bmSolution.widthFull))
        case _ => value
      }
    }

    val yInUse = if (isConstantMult) bmSolution.constant.get else if (bmSolution.multiplierType == SquareMultiplier) x else y
    val tempGolden = x * yInUse
    val tempRet = doRectangular(
      WeightedValue(x, ArithInfo(bmSolution.widthFull, 0)),
      WeightedValue(yInUse, ArithInfo(bmSolution.widthFull, 0)),
      bmSolution).eval
    val golden = adjustForMultType(tempGolden)
    val ret = adjustForMultType(tempRet)

    if (verbose) {
      val multName = s"${bmSolution.widthFull}-bit ${if (isConstantMult) "constant" else "variable"} ${className(bmSolution.multiplierType)} threshold = ${bmSolution.threshold}"
      logger.info(
        s"\n----$multName----" +
          s"\n\tdspCost = $multCost" +
          s"\n\tclbCost = ${splitCost + mergeCost + cmultCost} = $splitCost(split) + $mergeCost(merge) + $cmultCost(cmult)"
      )
    }

    if (bmSolution.multiplierType != MsbMultiplier) assert(ret == golden, s"x $x, y $y, yours $ret, golden $golden")
    else {
      val error = ret - golden
      assert(error <= 0 && error >= -(bmSolution.widthFull / 2), s"error = $error")
      if (verbose) logger.info(s"error introduced by MSB multiplier: $error, ${ret.bitLength}, ${golden.bitLength}")
    }
    ret
  }

  def implConstantMult(x: BigInt, verbose: Boolean = false): BigInt = impl(x, null, verbose)

  def selfTest(): Unit = {
    def getMultiplicand = BigInt(bmSolution.widthFull, Random)

    val data = if (bmSolution.multiplierType == SquareMultiplier) Seq.fill(1000)(getMultiplicand).map(x => (x, x))
    else Seq.fill(1000)(getMultiplicand, getMultiplicand)
    data.foreach { case (x, y) => impl(x, y) }
    logger.info("bm algo test passed")
  }

  /** --------
   * determine cost while initializing
   * -------- */
  impl(BigInt(bmSolution.widthFull, Random), BigInt(bmSolution.widthFull, Random), verbose = false)
  val eff = 1.0 // TODO: vary for different sizes
  val clbCost = (splitCost + fixCost + (mergeCost + cmultCost) / eff).toInt

  override def vivadoUtilEstimation = VivadoUtilEstimation(dsp = multCost, lut = clbCost, ff = clbCost * 2, bram36 = 0, uram288 = 0)
}