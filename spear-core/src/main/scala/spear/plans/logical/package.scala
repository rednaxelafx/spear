package spear.plans

import spear.Name
import spear.expressions.Expression
import spear.expressions.windows.{WindowSpec, WindowSpecRef}

package object logical {
  def table(name: Name): UnresolvedRelation = UnresolvedRelation(name)

  def values(expressions: Seq[Expression]): Project = SingleRowRelation select expressions

  def values(first: Expression, rest: Expression*): Project = values(first +: rest)

  def let(name: Name, plan: LogicalPlan)(body: LogicalPlan): With = {
    With(body, name, plan, None)
  }

  def let(name: Name, windowSpec: WindowSpec)(body: LogicalPlan): WindowDef =
    WindowDef(body, name, windowSpec)

  def let(name: Name, windowName: Name)(body: LogicalPlan): WindowDef =
    WindowDef(body, name, WindowSpecRef(windowName))
}
