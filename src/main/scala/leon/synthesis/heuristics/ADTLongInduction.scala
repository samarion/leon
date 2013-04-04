package leon
package synthesis
package heuristics

import solvers.TimeoutSolver
import purescala.Common._
import purescala.Trees._
import purescala.Extractors._
import purescala.TreeOps._
import purescala.TypeTrees._
import purescala.Definitions._

case object ADTLongInduction extends Rule("ADT Long Induction") with Heuristic {
  def instantiateOn(sctx: SynthesisContext, p: Problem): Traversable[RuleInstantiation] = {
    val tsolver = new TimeoutSolver(sctx.solver, 500L)
    val candidates = p.as.collect {
        case IsTyped(origId, AbstractClassType(cd)) if isInductiveOn(tsolver)(p.pc, origId) => (origId, cd)
    }

    val instances = for (candidate <- candidates) yield {
      val (origId, cd) = candidate
      val oas = p.as.filterNot(_ == origId)


      val resType = TupleType(p.xs.map(_.getType))

      val inductOn     = FreshIdentifier(origId.name, true).setType(origId.getType)
      val residualArgs = oas.map(id => FreshIdentifier(id.name, true).setType(id.getType))
      val residualMap  = (oas zip residualArgs).map{ case (id, id2) => id -> Variable(id2) }.toMap
      val residualArgDefs = residualArgs.map(a => VarDecl(a, a.getType))

      def isAlternativeRecursive(cd: CaseClassDef): Boolean = {
        cd.fieldsIds.exists(_.getType == origId.getType)
      }

      val isRecursive = cd.knownDescendents.exists {
        case ccd: CaseClassDef => isAlternativeRecursive(ccd)
        case _ => false
      }

      def childsOf(cd: AbstractClassDef): List[CaseClassDef] = {
        cd.knownDescendents.sortBy(_.id.name).toList.collect {
          case ccd: CaseClassDef =>
            ccd
        }
      }

      // Map for getting a formula in the context of within the recursive function
      val substMap = residualMap + (origId -> Variable(inductOn))


      if (isRecursive) {
        case class InductCase(ids: List[Identifier],
                              calls: List[Identifier],
                              pattern: Pattern,
                              outerPC: Expr,
                              trMap: Map[Identifier, Expr])

        val init = InductCase(inductOn :: residualArgs, List(), WildcardPattern(Some(inductOn)), BooleanLiteral(true), Map(inductOn -> Variable(inductOn)))

        def isRec(id: Identifier) = id.getType == origId.getType

        def unrollPattern(id: Identifier, ccd: CaseClassDef, withIds: List[Identifier])(on: Pattern): Pattern = on match {
          case WildcardPattern(Some(pid)) if pid == id =>
            CaseClassPattern(None, ccd, withIds.map(id => WildcardPattern(Some(id))))

          case CaseClassPattern(binder, sccd, sub) =>
            CaseClassPattern(binder, sccd, sub.map(unrollPattern(id, ccd, withIds) _))

          case _ => on
        }

        def unroll(ic: InductCase): List[InductCase] = {
          if (ic.ids.exists(isRec)) {
            val InductCase(ids, calls, pat, pc, trMap) = ic

            (for (id <- ids if isRec(id)) yield {
              for (ccd <- childsOf(cd)) yield {
                val subIds = ccd.fieldsIds.map(id => FreshIdentifier(id.name, true).setType(id.getType)).toList

                val newIds = ids.filterNot(_ == id) ++ subIds
                val newCalls = if (!subIds.isEmpty) {
                  List(subIds.find(isRec).get)
                } else {
                  List()
                }

                println(ccd)
                println(subIds)
                val newPattern = unrollPattern(id, ccd, subIds)(pat)

                val newMap = trMap.mapValues(v => substAll(Map(id -> CaseClass(ccd, subIds.map(Variable(_)))), v))

                InductCase(newIds, newCalls, newPattern, And(pc, CaseClassInstanceOf(ccd, Variable(id))), newMap)
              }
            }).flatten
          } else {
            List(ic)
          }
        }

        val cases = unroll(init).flatMap(unroll(_))

        val innerPhi = substAll(substMap, p.phi)
        val innerPC  = substAll(substMap, p.pc)

        val subProblemsInfo = for (c <- cases) yield {
          val InductCase(ids, calls, pat, pc, trMap) = c

          // generate subProblem

          var recCalls = Map[List[Identifier], List[Expr]]()

          val subPC = substAll(trMap, innerPC)
          val subPhi = substAll(trMap, innerPhi)

          var postXss = List[Identifier]()
          var postFs = List[Expr]()

          for (cid <- calls) {
            val postXs  = p.xs map (id => FreshIdentifier("r", true).setType(id.getType))
            postXss = postXss ::: postXs
            val postXsMap = (p.xs zip postXs).toMap.mapValues(Variable(_))
            postFs = substAll(postXsMap + (inductOn -> Variable(cid)), innerPhi) :: postFs

            recCalls += postXs -> (Variable(cid) +: residualArgs.map(id => Variable(id)))
          }

          val subProblem = Problem(c.ids ::: postXss, And(subPC :: postFs), subPhi, p.xs)
          println(subProblem)
          println(recCalls)
          (subProblem, pat, recCalls, pc)
        }

        val onSuccess: List[Solution] => Option[Solution] = {
          case sols =>
            var globalPre = List[Expr]()

            val newFun = new FunDef(FreshIdentifier("rec", true), resType, VarDecl(inductOn, inductOn.getType) +: residualArgDefs)

            val cases = for ((sol, (problem, pat, calls, pc)) <- (sols zip subProblemsInfo)) yield {
              globalPre ::= And(pc, sol.pre)

              SimpleCase(pat, calls.foldLeft(sol.term){ case (t, (binders, callargs)) => LetTuple(binders, FunctionInvocation(newFun, callargs), t) })
            }

            // Might be overly picky with obviously true pre (a.is[Cons] OR a.is[Nil])
            if (false && sols.exists(_.pre != BooleanLiteral(true))) {
              // Required to avoid an impossible cases, which suffices to
              // allow invalid programs. This might be too strong though: we
              // might only have to enforce it on solutions of base cases.
              None
            } else {
              val funPre = substAll(substMap, And(p.pc, Or(globalPre)))
              val funPost = substAll(substMap, p.phi)
              val outerPre = Or(globalPre)

              newFun.precondition = Some(funPre)
              newFun.postcondition = Some(LetTuple(p.xs.toSeq, ResultVariable().setType(resType), funPost))

              newFun.body = Some(MatchExpr(Variable(inductOn), cases))

              Some(Solution(Or(globalPre), 
                            sols.flatMap(_.defs).toSet+newFun,
                            FunctionInvocation(newFun, Variable(origId) :: oas.map(Variable(_)))
                          ))
            }
        }

        Some(HeuristicInstantiation(p, this, subProblemsInfo.map(_._1).toList, onSuccess, "ADT Long Induction on '"+origId+"'"))
      } else {
        None
      }
    }

    instances.flatten
  }
}