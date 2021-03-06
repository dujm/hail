package is.hail.methods

import is.hail.annotations._
import is.hail.expr._
import is.hail.keytable.KeyTable
import is.hail.utils._
import is.hail.variant.VariantSampleMatrix
import org.apache.spark.mllib.linalg.DenseMatrix

object PCA {
  def pcSchema(k: Int, asArray: Boolean = false): Type =
    if (asArray)
      TArray(TFloat64())
    else
      TStruct((1 to k).map(i => (s"PC$i", TFloat64())): _*)

  //returns (sample scores, variant loadings, eigenvalues)
  def apply(vsm: VariantSampleMatrix, expr: String, k: Int, computeLoadings: Boolean, asArray: Boolean = false): (IndexedSeq[Double], DenseMatrix, Option[KeyTable]) = {
    val sc = vsm.sparkContext
    val (maybeVariants, mat) = vsm.toIndexedRowMatrix(expr, computeLoadings)
    val svd = mat.computeSVD(k, computeLoadings)
    if (svd.s.size < k)
      fatal(
        s"""Found only ${ svd.s.size } non-zero (or nearly zero) eigenvalues, but user requested ${ k }
           |principal components.""".stripMargin)

    val optionLoadings = someIf(computeLoadings, {
      val rowType = TStruct("v" -> vsm.vSignature, "pcaLoadings" -> pcSchema(k, asArray))
      val rowTypeBc = vsm.sparkContext.broadcast(rowType)
      val variantsBc = vsm.sparkContext.broadcast(maybeVariants.get)
      val rdd = svd.U.rows.mapPartitions[RegionValue] { it =>
        val region = MemoryBuffer()
        val rv = RegionValue(region)
        val rvb = new RegionValueBuilder(region)
        it.map { ir =>
          rvb.start(rowTypeBc.value)
          rvb.startStruct()
          rvb.addAnnotation(rowTypeBc.value.fieldType(0), variantsBc.value(ir.index.toInt))
          if (asArray) rvb.startArray(k) else rvb.startStruct()
          var i = 0
          while (i < k) {
            rvb.addDouble(ir.vector(i))
            i += 1
          }
          if (asArray) rvb.endArray() else rvb.endStruct()
          rvb.endStruct()
          rv.setOffset(rvb.end())
          rv
        }
      }
      new KeyTable(vsm.hc, rdd, rowType, Array("v"))
    })

    (svd.s.toArray.map(math.pow(_, 2)), svd.V.multiply(DenseMatrix.diag(svd.s)), optionLoadings)
  }
}
