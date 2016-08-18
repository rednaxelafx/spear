package scraper

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

import scraper.exceptions.{AnalysisException, TableNotFoundException}
import scraper.expressions._
import scraper.plans.logical.LogicalPlan

trait Catalog {
  val functionRegistry: FunctionRegistry

  def registerRelation(tableName: Name, analyzedPlan: LogicalPlan): Unit

  def removeRelation(tableName: Name): Unit

  def lookupRelation(tableName: Name): LogicalPlan
}

class InMemoryCatalog extends Catalog {
  override val functionRegistry: FunctionRegistry = {
    val registry = new InMemoryFunctionRegistry

    Seq(
      function[Coalesce](i"coalesce"),
      function[Rand](i"rand"),
      function[CollectList](i"collect_list"),
      function[CollectSet](i"collect_set"),

      function[Count](i"count"),
      function[FirstValue](i"first_value"),
      function[LastValue](i"last_value"),
      function[Max](i"max"),
      function[Min](i"min"),
      function[Average](i"average"),
      function[Average](i"avg"),
      function[Sum](i"sum"),
      function[Product_](i"product"),
      function[BoolAnd](i"bool_and"),
      function[BoolOr](i"bool_or"),

      function[Concat](i"concat"),

      function[CreateNamedStruct](i"named_struct"),
      function[CreateArray](i"array"),
      function[CreateMap](i"map")
    ) foreach registry.registerFunction

    registry
  }

  private def function[T <: Expression: ClassTag](name: Name): FunctionInfo = {
    val classTag = implicitly[ClassTag[T]]
    val builder = (args: Seq[Expression]) => Try {
      val argClasses = Seq.fill(args.length)(classOf[Expression])
      classTag.runtimeClass.getDeclaredConstructor(argClasses: _*)
    } map {
      _.newInstance(args: _*) match {
        case fn: Expression => fn
      }
    } match {
      case Success(fn)    => fn
      case Failure(cause) => throw new AnalysisException(cause.getMessage, cause)
    }

    FunctionInfo(name, builder)
  }

  override def registerRelation(tableName: Name, analyzedPlan: LogicalPlan): Unit =
    tables(tableName) = analyzedPlan

  override def removeRelation(tableName: Name): Unit = tables -= tableName

  override def lookupRelation(tableName: Name): LogicalPlan =
    tables
      .get(tableName)
      .map(_ subquery tableName)
      .getOrElse(throw new TableNotFoundException(tableName))

  private val tables: mutable.Map[Name, LogicalPlan] = mutable.Map.empty
}
