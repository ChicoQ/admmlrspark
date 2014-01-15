package com.tulloch.admmlrspark

import ADMMOptimizer._
import DenseVectorImplicits._
import breeze.linalg.DenseVector
import breeze.optimize.{DiffFunction, LBFGS}
import org.apache.spark.mllib.classification.LogisticRegressionModel
import org.apache.spark.mllib.regression.{GeneralizedLinearAlgorithm, LabeledPoint}
import org.apache.spark.mllib.util.DataValidators
import org.apache.spark.rdd.RDD
import org.apache.spark.util.Vector

case class SparseLogisticRegressionADMMPrimalUpdater(
  lambda: Double,
  rho: Double,
  lbfgsMaxNumIterations: Int = 5,
  lbfgsHistory: Int = 10,
  lbfgsTolerance: Double = 1E-4) extends ADMMPrimalUpdater {

  def xUpdate(state: ADMMState): ADMMState = {
      // Our convex objective function that we seek to optimize
    val f = new DiffFunction[DenseVector[Double]] {
      def calculate(x: DenseVector[Double]) = {
        (objective(state)(x), gradient(state)(x))
      }
    }

    // TODO(tulloch) - it would be nice to have relative tolerance and
    // absolute tolerance here.
    val lbfgs = new LBFGS[DenseVector[Double]](
      maxIter = lbfgsMaxNumIterations,
      m = lbfgsHistory,
      tolerance = lbfgsTolerance)

    val xNew = lbfgs.minimize(f, state.x) // this is the "warm start" approach
    state.copy(x = xNew)
  }

  def zUpdate(states: RDD[ADMMState]): RDD[ADMMState] = {
    val numStates = states.count
    // TODO(tulloch) - is this epsilon > 0 a hack?
    val epsilon = 0.00001 // avoid division by zero for shrinkage

    // TODO(tulloch) - make sure this only sends x, u to the reducer
    // instead of the full ADMM state.
    val xBar = average(states.map(_.x))
    val uBar = average(states.map(_.u))

    val zNew = Vector((xBar + uBar)
      .elements
      .map(shrinkage(lambda / (rho * numStates + epsilon))))

    states.map(_.copy(z = zNew))
  }

  def average(updates: RDD[Vector]): Vector = {
    updates.reduce(_ + _) / updates.count
  }

  def objective(state: ADMMState)(weights: Vector): Double = {
    val lossObjective = state.points
      .map(lp => {
        val margin = lp.label * (weights dot Vector(lp.features))
        -logPhi(margin)
      })
      .sum

    val regularizerObjective = (weights - state.z + state.u).squaredNorm
    val totalObjective = lossObjective + rho / 2 * regularizerObjective
    totalObjective
  }

  def gradient(state: ADMMState)(weights: Vector): Vector = {
    val lossGradient = state.points
      .map(lp => {
        val margin = lp.label * (weights dot Vector(lp.features))
        lp.label * Vector(lp.features) * (phi(margin) - 1)
      })
      .reduce(_ + _)

    val regularizerGradient = 2 * (weights - state.z + state.u)
    val totalGradient = lossGradient + rho / 2 * regularizerGradient
    totalGradient
  }

  private def clampToRange(lower: Double, upper: Double)(margin: Double): Double =
    math.min(upper, math.max(lower, margin))

  private def logPhi(margin: Double): Double = {
    // TODO(tulloch) - do we need to clamp here?
    val t = clampToRange(-10, 10)(margin)
    math.log(1.0 / (1.0 + math.exp(-t)))
  }

  private def phi(margin: Double): Double = {
    // TODO(tulloch) - do we need to clamp here?
    val t = clampToRange(-10, 10)(margin)
    if (t > 0) 1.0 / (1 + math.exp(-t)) else math.exp(t) / (1 + math.exp(t))
  }
}

class SparseLogisticRegressionWithADMM(
  val numIterations: Int,
  val lambda: Double,
  val rho: Double)
    extends GeneralizedLinearAlgorithm[LogisticRegressionModel]
    with Serializable {

  override val optimizer = new ADMMOptimizer(
    numIterations,
    new SparseLogisticRegressionADMMPrimalUpdater(lambda = lambda, rho = rho))

  override val validators = List(DataValidators.classificationLabels)

  override def createModel(
    weights: Array[Double],
    intercept: Double): LogisticRegressionModel = {
    new LogisticRegressionModel(weights, intercept)
  }
}

object SparseLogisticRegressionWithADMM {
  def train(
    input: RDD[LabeledPoint],
    numIterations: Int,
    lambda: Double,
    rho: Double) = {
    new SparseLogisticRegressionWithADMM(numIterations, lambda, rho).run(input)
  }
}
