package scraper.expressions

import scala.math.pow
import scala.util.Try

import scraper.{NullSafeOrdering, Row}
import scraper.expressions.functions.lit
import scraper.expressions.typecheck.{Foldable, TypeConstraint}
import scraper.types._

trait ArithmeticExpression extends Expression {
  lazy val numeric: Numeric[Any] = dataType match {
    case t: NumericType => t.genericNumeric
  }
}

trait UnaryArithmeticOperator extends UnaryOperator with ArithmeticExpression {
  override protected def typeConstraint: TypeConstraint = children sameSubtypeOf NumericType

  override protected lazy val strictDataType: DataType = child.dataType
}

case class Negate(child: Expression) extends UnaryArithmeticOperator {
  override def operator: String = "-"

  override def nullSafeEvaluate(value: Any): Any = numeric.negate(value)
}

case class Positive(child: Expression) extends UnaryArithmeticOperator {
  override def operator: String = "+"

  override def nullSafeEvaluate(value: Any): Any = value
}

trait BinaryArithmeticOperator extends ArithmeticExpression with BinaryOperator {
  override protected def typeConstraint: TypeConstraint =
    children sameSubtypeOf NumericType orElse {
      left subtypeOf NumericType concat (right sameTypeAs StringType andAlso Foldable) andAlso {
        case Seq(lhs @ NumericType(resultType), rhs @ StringType(_)) =>
          new TypeConstraint {
            override def enforced: Try[Seq[Expression]] =
              Try { (rhs cast resultType).evaluated } map lit map { lhs :: _ :: Nil }
          }
      }
    } orElse {
      left sameTypeAs StringType andAlso Foldable concat (right subtypeOf NumericType) andAlso {
        case Seq(lhs @ StringType(_), rhs @ NumericType(resultType)) =>
          new TypeConstraint {
            override def enforced: Try[Seq[Expression]] =
              Try { (lhs cast resultType).evaluated } map lit map { _ :: rhs :: Nil }
          }
      }
    }

  override protected lazy val strictDataType: DataType = left.dataType
}

case class Plus(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any = numeric.plus(lhs, rhs)

  override def operator: String = "+"
}

case class Minus(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any = numeric.minus(lhs, rhs)

  override def operator: String = "-"
}

case class Multiply(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any = numeric.times(lhs, rhs)

  override def operator: String = "*"
}

case class Divide(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  private lazy val div = whenStrictlyTyped {
    dataType match {
      case t: FractionalType => t.genericFractional.div _
      case t: IntegralType   => t.genericIntegral.quot _
    }
  }

  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any = div(lhs, rhs)

  override def operator: String = "/"
}

case class Remainder(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  override protected def typeConstraint: TypeConstraint =
    super.typeConstraint andAlso (_ sameSubtypeOf IntegralType)

  override protected lazy val strictDataType: DataType = left.dataType

  private lazy val integral = whenStrictlyTyped(dataType match {
    case t: IntegralType => t.genericIntegral
  })

  override def operator: String = "%"

  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any = integral.rem(lhs, rhs)
}

case class Power(left: Expression, right: Expression) extends BinaryArithmeticOperator {
  override protected def typeConstraint: TypeConstraint =
    super.typeConstraint andAlso { _ sameTypeAs DoubleType }

  override def dataType: DataType = DoubleType

  override def operator: String = "^"

  override def nullSafeEvaluate(lhs: Any, rhs: Any): Any =
    pow(lhs.asInstanceOf[Double], rhs.asInstanceOf[Double])
}

case class IsNaN(child: Expression) extends UnaryExpression {
  override protected def typeConstraint: TypeConstraint = child subtypeOf FractionalType

  override def dataType: DataType = BooleanType

  override def evaluate(input: Row): Any = {
    val value = child evaluate input
    if (value == null) false else (value, dataType) match {
      case (v: Double, DoubleType) => lit(v).isNaN
      case (v: Float, FloatType)   => lit(v).isNaN
      case _                       => false
    }
  }
}

abstract class GreatestLike extends Expression {
  assert(children.nonEmpty)

  protected def nullsLarger: Boolean

  override protected lazy val strictDataType: DataType = children.head.dataType

  override protected def typeConstraint: TypeConstraint = children sameSubtypeOf OrderedType

  protected lazy val ordering = new NullSafeOrdering(strictDataType, nullsLarger)
}

case class Greatest(children: Seq[Expression]) extends GreatestLike {
  override protected def nullsLarger: Boolean = false

  override def evaluate(input: Row): Any = children map { _ evaluate input } max ordering
}

object Greatest {
  def apply(first: Expression, rest: Expression*): Greatest = Greatest(first +: rest)
}

case class Least(children: Seq[Expression]) extends GreatestLike {
  override protected def nullsLarger: Boolean = true

  override def evaluate(input: Row): Any = children map { _ evaluate input } min ordering
}

object Least {
  def apply(first: Expression, rest: Expression*): Least = Least(first +: rest)
}
