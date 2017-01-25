package scraper.parsers

import fastparse.all._

import scraper.Name
import scraper.annotations.ExtendedSQLSyntax
import scraper.expressions._
import scraper.plans.logical._

// SQL06 section 7.6
object TableReferenceParser extends LoggingParser {
  import IdentifierParser._
  import JoinedTableParser._
  import KeywordParser._
  import NameParser._
  import SubqueryParser._
  import WhitespaceApi._

  private val correlationClause: P[LogicalPlan => LogicalPlan] = (
    AS.? ~ identifier
    map { name => (_: LogicalPlan) subquery name }
    opaque "correlation-clause"
  )

  private val tableOrQueryName: P[LogicalPlan] =
    tableName map { _.table } map table opaque "table-or-query-name"

  private val derivedTable: P[LogicalPlan] =
    tableSubquery opaque "derived-table"

  private val tablePrimary: P[LogicalPlan] = (
    tableOrQueryName ~ correlationClause.?.map { _ getOrElse identity[LogicalPlan] _ }
    | derivedTable ~ correlationClause
    map { case (name, f) => f(name) }
    opaque "table-primary"
  )

  val tableFactor: P[LogicalPlan] = tablePrimary opaque "table-factor"

  val columnNameList: P[Seq[Name]] = columnName rep (min = 1, sep = ",") opaque "column-name-list"

  private val tablePrimaryOrJoinedTable: P[LogicalPlan] =
    joinedTable | tablePrimary opaque "table-primary-or-joined-table"

  val tableReference: P[LogicalPlan] = tablePrimaryOrJoinedTable opaque "table-reference"
}

// SQL06 section 7.7
object JoinedTableParser extends LoggingParser {
  import KeywordParser._
  import SearchConditionParser._
  import TableReferenceParser._
  import WhitespaceApi._

  private val outerJoinType: P[JoinType] = (
    (LEFT attach LeftOuter)
    | (RIGHT attach RightOuter)
    | (FULL attach FullOuter)
  ) ~ OUTER.? opaque "outer-join-type"

  private val joinType: P[JoinType] =
    (INNER attach Inner) | outerJoinType opaque "joinType"

  private val joinCondition: P[Expression] =
    ON ~ searchCondition opaque "join-condition"

  private val joinSpecification: P[Expression] =
    joinCondition opaque "join-specification"

  private val qualifiedJoin: P[LogicalPlan] = (
    (P(tableFactor) | P(tableReference))
    ~ joinType.? ~ JOIN
    ~ P(tableReference)
    ~ joinSpecification.? map {
      case (lhs, maybeType, rhs, maybeCondition) =>
        Join(lhs, rhs, maybeType getOrElse Inner, maybeCondition)
    } opaque "qualified-join"
  )

  private val crossJoin: P[LogicalPlan] =
    (P(tableFactor) | P(tableReference)) ~ CROSS ~ JOIN ~ tableFactor map {
      case (lhs, rhs) => Join(lhs, rhs, Inner, None)
    } opaque "cross-join"

  val joinedTable: P[LogicalPlan] =
    crossJoin | qualifiedJoin opaque "joined-table"
}

// SQL06 section 7.9
object GroupByClauseParser extends LoggingParser {
  import ColumnReferenceParser._
  import KeywordParser._
  import ValueExpressionParser._
  import WhitespaceApi._

  @ExtendedSQLSyntax
  private val groupingColumnReference: P[Expression] =
    columnReference | valueExpression opaque "grouping-column-reference"

  private val groupingColumnReferenceList: P[Seq[Expression]] =
    groupingColumnReference rep (min = 1, sep = ",") opaque "grouping-column-reference-list"

  private val ordinaryGroupingSet: P[Seq[Expression]] = (
    groupingColumnReference.map { _ :: Nil }
    | "(" ~ groupingColumnReferenceList ~ ")"
    opaque "ordinary-grouping-set"
  )

  private val groupingElement: P[Seq[Expression]] =
    ordinaryGroupingSet opaque "grouping-element"

  private val groupingElementList: P[Seq[Expression]] = (
    groupingElement
    rep (min = 1, sep = ",")
    map { _.flatten }
    opaque "grouping-element-list"
  )

  val groupByClause: P[Seq[Expression]] =
    GROUP ~ BY ~ groupingElementList opaque "group-by-clause"
}

// SQL06 section 7.12
object QuerySpecificationParser extends LoggingParser {
  import AggregateFunctionParser._
  import GroupByClauseParser._
  import IdentifierParser._
  import KeywordParser._
  import NameParser._
  import SearchConditionParser._
  import TableReferenceParser._
  import ValueExpressionParser._
  import WhitespaceApi._

  private val asClause: P[Name] =
    AS.? ~ columnName opaque "as-clause"

  private val derivedColumn: P[NamedExpression] = (
    (valueExpression ~ asClause).map { case (e, a) => e as a }
    | valueExpression.map { NamedExpression.named }
    opaque "derived-column"
  )

  private val asteriskedIdentifier: P[Name] =
    identifier opaque "asterisked-identifier"

  private val asteriskedIdentifierChain: P[Seq[Name]] =
    asteriskedIdentifier rep (min = 1, sep = ".") opaque "asterisked-identifier-chain"

  val qualifiedAsterisk: P[NamedExpression] = (
    asteriskedIdentifierChain ~ "." ~ "*"
    map { case Seq(qualifier) => * of qualifier }
    opaque "qualified-asterisk"
  )

  private val selectSublist: P[NamedExpression] = (
    qualifiedAsterisk
    | derivedColumn
    opaque "select-sublist"
  )

  private val selectList: P[Seq[NamedExpression]] = (
    (P("*") attach * :: Nil)
    | selectSublist.rep(min = 1, sep = ",")
    opaque "select-list"
  )

  private val tableReferenceList: P[LogicalPlan] = (
    tableReference
    rep (min = 1, sep = ",")
    map { _ reduce { _ join _ } }
    opaque "table-reference-list"
  )

  private val fromClause: P[LogicalPlan] =
    FROM ~ tableReferenceList opaque "from-clause"

  private val whereClause: P[LogicalPlan => LogicalPlan] = (
    WHERE ~ searchCondition
    map { cond => (_: LogicalPlan) filter cond }
    opaque "where-clause"
  )

  private val havingClause: P[LogicalPlan => LogicalPlan] = (
    HAVING ~ searchCondition
    map { cond => (_: LogicalPlan) filter cond }
    opaque "having-clause"
  )

  private val orderingSpecification: P[Expression => SortOrder] = (
    ASC.attach { (_: Expression).asc }
    | DESC.attach { (_: Expression).desc }
    opaque "ordering-specification"
  )

  private val nullOrdering: P[SortOrder => SortOrder] =
    NULLS ~ (
      FIRST.attach { (_: SortOrder).nullsFirst }
      | LAST.attach { (_: SortOrder).nullsLast }
    ) opaque "null-ordering"

  private val sortKey: P[Expression] =
    valueExpression opaque "sort-key"

  private val sortSpecification: P[SortOrder] = (
    sortKey
    ~ orderingSpecification.?.map { _ getOrElse { (_: Expression).asc } }
    ~ nullOrdering.?.map { _ getOrElse { (_: SortOrder).nullsLarger } }
    map { case (key, f, g) => f andThen g apply key }
    opaque "sort-specification"
  )

  private val sortSpecificationList: P[Seq[SortOrder]] =
    sortSpecification rep (min = 1, sep = ",") opaque "sort-specification-list"

  private val orderByClause: P[LogicalPlan => LogicalPlan] = (
    ORDER ~ BY ~ sortSpecificationList
    map { sortOrders => (_: LogicalPlan) orderBy sortOrders }
    opaque "order-by-clause"
  )

  val querySpecification: P[LogicalPlan] = (
    SELECT
    ~ setQuantifier.?.map { _ getOrElse identity[LogicalPlan] _ }
    ~ selectList
    ~ fromClause.?.map { _ getOrElse SingleRowRelation }
    ~ whereClause.?.map { _ getOrElse identity[LogicalPlan] _ }
    ~ groupByClause.?
    ~ havingClause.?.map { _ getOrElse identity[LogicalPlan] _ }
    ~ orderByClause.?.map { _ getOrElse identity[LogicalPlan] _ } map {
      case (quantify, projectList, relation, filter, maybeGroups, having, orderBy) =>
        val projectOrAgg = maybeGroups map { groups =>
          (_: LogicalPlan) groupBy groups agg projectList
        } getOrElse {
          (_: LogicalPlan) select projectList
        }

        filter andThen projectOrAgg andThen quantify andThen having andThen orderBy apply relation
    }
    opaque "query-specification"
  )
}

// SQL06 section 7.13
object QueryExpressionParser extends LoggingParser {
  import KeywordParser._
  import NameParser._
  import NumericParser._
  import QuerySpecificationParser._
  import TableReferenceParser._
  import WhitespaceApi._

  private val withColumnList: P[Seq[Name]] =
    columnNameList opaque "with-column-list"

  private val withListElement: P[LogicalPlan => LogicalPlan] =
    queryName ~ ("(" ~ withColumnList ~ ")").? ~ AS ~ "(" ~ P(queryExpression) ~ ")" map {
      case (name, maybeColumns, query) =>
        (child: LogicalPlan) => With(child, name, query, maybeColumns)
    } opaque "with-list-element"

  private val withList: P[LogicalPlan => LogicalPlan] = (
    withListElement rep (min = 1, sep = ",")
    map { _ reduce { _ andThen _ } }
    opaque "with-list"
  )

  private val withClause: P[LogicalPlan => LogicalPlan] =
    WITH ~ withList opaque "with-clause"

  private val simpleTable: P[LogicalPlan] =
    querySpecification opaque "query-specification"

  private val queryPrimary: P[LogicalPlan] = (
    "(" ~ P(queryExpressionBody) ~ ")"
    | simpleTable
    opaque "query-primary"
  )

  private val queryTerm: P[LogicalPlan] =
    queryPrimary chain (INTERSECT attach Intersect) opaque "query-term"

  private lazy val queryExpressionBody: P[LogicalPlan] =
    queryTerm chain (
      (UNION ~ ALL.? attach Union) | (EXCEPT attach Except)
    ) opaque "query-expression-body"

  @ExtendedSQLSyntax
  private val limitClause: P[LogicalPlan => LogicalPlan] = (
    LIMIT ~ unsignedInteger filter { _.isValidInt }
    map { n => (_: LogicalPlan) limit n.toInt }
    opaque "limit-clause"
  )

  lazy val queryExpression: P[LogicalPlan] = (
    withClause.?.map { _ getOrElse identity[LogicalPlan] _ }
    ~ queryExpressionBody
    ~ limitClause.?.map { _ getOrElse identity[LogicalPlan] _ }
    map { case (cte, query, limit) => limit andThen cte apply query }
    opaque "query-expression"
  )
}

// SQL06 section 7.15
object SubqueryParser extends LoggingParser {
  import QueryExpressionParser._
  import WhitespaceApi._

  private val subquery: P[LogicalPlan] = "(" ~ P(queryExpression) ~ ")" opaque "subquery"

  val tableSubquery: P[LogicalPlan] = subquery opaque "table-subquery"
}