package org.broadinstitute.hail.io.gen

import org.apache.spark.rdd.RDD
import org.apache.spark.{Accumulable, SparkContext}
import org.broadinstitute.hail.Utils._
import org.broadinstitute.hail.annotations._
import org.broadinstitute.hail.expr._
import org.broadinstitute.hail.io.bgen.BgenLoader
import org.broadinstitute.hail.variant._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class GenResult(file: String, nSamples: Int, nVariants: Int, rdd: RDD[(Variant, Annotation, Iterable[Genotype])])

class InfoScoreCalculator {
  var e = ArrayBuffer.empty[Double]
  var f = ArrayBuffer.empty[Double]
  var N = 0

  def thetaHat: Option[Double] = if (N != 0) Some(e.sum / (2*N)) else None

  def infoScore: Option[Double] = {
    thetaHat.map{case t =>
      assert(t >= 0.0 && t <= 1.0)

      if (t == 1.0 || t == 0.0)
        1.0
      else
        1.0 - ( e.zip(f).map{case (ei, fi) => fi - math.pow(ei, 2)}.sum / (2 * N * t * (1.0 - t)))
    }
  }

  def addDosage(d: Array[Double]) {
    e += (d(1) + 2*d(2))
    f += (d(1) + 4*d(2))
    N += 1
  }
}

object GenReport {
  val dosageNoCall = 1
  val dosageLessThanTolerance = 2

  var accumulators: List[(String, Accumulable[mutable.Map[Int, Int], Int])] = Nil

  def warningMessage(id: Int, count: Int): String = {
    val desc = id match {
      case `dosageNoCall` => "Dosage triple of (0.0,0.0,0.0)"
      case `dosageLessThanTolerance` => "Sum of Dosage < (1.0 - tolerance)"
      case _ => throw new UnsupportedOperationException
    }
    s"$count ${plural(count, "time")}: $desc"
  }

  def report() {
    val sb = new StringBuilder()

    for ((file, m) <- accumulators) {
      sb.clear()

      sb.append(s"while importing:\n    $file")

      val genotypeWarnings = m.value
      val nGenotypesFiltered = genotypeWarnings.values.sum
      if (nGenotypesFiltered > 0) {
        sb.append(s"\n  filtered $nGenotypesFiltered genotypes:")
        genotypeWarnings.foreach { case (id, n) =>
          if (n > 0) {
            sb.append("\n    ")
            sb.append(warningMessage(id, n))
          }
        }
      }

      if (nGenotypesFiltered == 0) {
        sb.append("  import clean")
        info(sb.result())
      } else
        warn(sb.result())
    }
  }
}

object GenUtils {

  def normalizePPs(arr: Array[Double]): Array[Double] = {
    val sum = arr.sum
    if (sum != 0.0)
      if (math.abs(sum - 1.0) > 3.0e-4)
        arr.map{_ / sum}
      else
        arr
    else
      Array(0.3333, 0.3333, 0.3333)
  }

  def convertProbsToInt(prob: Double): Int = {
    val tmp = prob * 32768
    require(tmp >= 0 && tmp < 65535.5)
    math.round(tmp).toInt
  }

  def convertProbsToInt(probArray: Array[Double]): Array[Int] = probArray.map{ d => convertProbsToInt(d)}
}

object GenLoader2 {
  def apply(genFile: String, sampleFile: String, sc: SparkContext, nPartitions: Option[Int] = None, tolerance: Double = 0.02): GenResult = {
    val hConf = sc.hadoopConfiguration
    val sampleIds = BgenLoader.readSampleFile(hConf, sampleFile)
    val nSamples = sampleIds.length

    val reportAcc = sc.accumulable[mutable.Map[Int, Int], Int](mutable.Map.empty[Int, Int])
    GenReport.accumulators ::=(genFile, reportAcc)

    val rdd = sc.textFile(genFile, nPartitions.getOrElse(sc.defaultMinPartitions))
        .map{ case line => readGenLine(line, nSamples, tolerance, reportAcc)}

    val signatures = TStruct("rsid" -> TString, "varid" -> TString, "infoScore" -> TDouble)

    GenResult(genFile, nSamples, rdd.count().toInt, rdd = rdd)
  }

  def readGenLine(line: String, nSamples: Int, tolerance: Double, reportAcc: Accumulable[mutable.Map[Int, Int], Int]): (Variant, Annotation, Iterable[Genotype]) = {
    val arr = line.split("\\s+")
    val rsid = arr(2)
    val varid = arr(1)
    val variant = Variant(arr(0), arr(3).toInt, arr(4), arr(5))
    val dosages = arr.drop(6).map {_.toDouble}

    if (dosages.length != (3 * nSamples))
      fatal("Number of dosages does not match number of samples")

    val dosageArray = new Array[Int](3)
    val b = new GenotypeStreamBuilder(variant) //FIXME: Add compression flag to apply
    val genoBuilder = new GenotypeBuilder(variant)
    val infoScoreCalculator = new InfoScoreCalculator

    for (i <- dosages.indices by 3) {
      genoBuilder.clear()

      genoBuilder.setDosageFlag()

      val origDosages = Array(dosages(i), dosages(i+1), dosages(i+2))
      val sumDosages = origDosages.sum

      if (sumDosages == 0.0)
        reportAcc += GenReport.dosageNoCall
      else if (sumDosages < (1.0 - tolerance))
        reportAcc += GenReport.dosageLessThanTolerance

      if (origDosages.sum >= (1 - tolerance)) {
        val normProbs = GenUtils.normalizePPs(origDosages)

        infoScoreCalculator.addDosage(normProbs) //FIXME: Should dosages be here or before normalization?

        val dosageAA = GenUtils.convertProbsToInt(normProbs(0))
        val dosageAB = GenUtils.convertProbsToInt(normProbs(1))
        val dosageBB = GenUtils.convertProbsToInt(normProbs(2))

        val sumDosage = dosageAA + dosageAB + dosageBB

        assert(sumDosage >= 32765 && sumDosage <= 32771)

        val gt = if (dosageAA > dosageAB && dosageAA > dosageBB)
          0
        else if (dosageAB > dosageAA && dosageAB > dosageBB)
          1
        else if (dosageBB > dosageAA && dosageBB > dosageAB)
          2
        else
          -1

        if (gt >= 0) {
          genoBuilder.setGT(gt)
        }

        genoBuilder.setDosage(Array(dosageAA, dosageAB, dosageBB))

      }
      b.write(genoBuilder)
    }

    val infoScore = infoScoreCalculator.infoScore.map{case d => (d * 10000).round / 10000.0}
    val annotations = Annotation(rsid, varid, infoScore)

    (variant, annotations, b.result())
  }
}