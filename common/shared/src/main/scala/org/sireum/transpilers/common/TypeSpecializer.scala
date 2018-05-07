// #Sireum

package org.sireum.transpilers.common

import org.sireum._
import org.sireum.lang.ast.ResolvedAttr
import org.sireum.message._
import org.sireum.lang.{ast => AST}
import org.sireum.lang.symbol._
import org.sireum.lang.symbol.Resolver._
import org.sireum.lang.tipe.TypeHierarchy

object TypeSpecializer {

  @datatype trait EntryPoint

  object EntryPoint {

    @datatype class Method(name: QName) extends EntryPoint

    @datatype class Worksheet(program: AST.TopUnit.Program) extends EntryPoint

  }

  @datatype class Result(
    typeHierarchy: TypeHierarchy,
    entryPoints: ISZ[EntryPoint],
    nameTypes: HashMap[QName, HashSet[AST.Typed.Name]],
    otherTypes: HashSet[AST.Typed],
    objectVars: HashSet[QName],
    classVars: HashSet[QName],
    methods: HashMap[QName, HashSet[Method]],
    funs: HashMap[QName, HashSet[Fun]]
  )

  @datatype class Method(
    isInObject: B,
    isNested: B,
    owner: QName,
    id: String,
    ast: AST.Stmt.Method,
    closureEnv: HashMap[String, AST.Typed]
  )

  @datatype class Fun(ast: AST.Exp.Fun)

  val tsKind: String = "Type Specializer"

  def specialize(th: TypeHierarchy, entryPoints: ISZ[EntryPoint], reporter: Reporter): Result = {
    val r = TypeSpecializer(th, entryPoints).specialize()
    return r
  }
}

import TypeSpecializer._

@record class TypeSpecializer(th: TypeHierarchy, eps: ISZ[EntryPoint]) extends AST.MTransformer {
  val reporter: Reporter = Reporter.create
  var nameTypes: HashMap[QName, HashSet[AST.Typed.Name]] = HashMap.empty
  var otherTypes: HashSet[AST.Typed] = HashSet.empty
  var objectVars: HashSet[QName] = HashSet.empty
  var classVars: HashSet[QName] = HashSet.empty
  var methods: HashMap[QName, HashSet[Method]] = HashMap.empty
  var funs: HashMap[QName, HashSet[Fun]] = HashMap.empty
  var substMap: HashMap[String, AST.Typed] = HashMap.empty

  def specialize(): TypeSpecializer.Result = {

    def specializeMethod(ep: EntryPoint.Method): Unit = {
      val info: Info.Method = th.nameMap.get(ep.name) match {
        case Some(inf: Info.Method) => inf
        case Some(_) =>
          reporter.error(None(), tsKind, st"'${(ep.name, ".")}' is not a method.".render)
          return
        case _ =>
          reporter.error(None(), tsKind, st"Could not find method entry point '${(ep.name, ".")}'.".render)
          return
      }
      if (info.ast.sig.typeParams.nonEmpty) {
        reporter.error(None(), tsKind, st"Method entry point '${(ep.name, ".")}' cannot be generic.".render)
        return
      }
      transformStmt(info.ast)
    }

    def specializeWorksheet(ep: EntryPoint.Worksheet): Unit = {
      for (stmt <- ep.program.body.stmts) {
        transformStmt(stmt)
      }
    }

    for (ep <- eps) {
      ep match {
        case ep: EntryPoint.Method => specializeMethod(ep)
        case ep: EntryPoint.Worksheet => specializeWorksheet(ep)
      }
    }
    return TypeSpecializer.Result(th, eps, nameTypes, otherTypes, objectVars, classVars, methods, funs)
  }

  override def postResolvedAttr(o: ResolvedAttr): MOption[ResolvedAttr] = {
    o.resOpt.get match {
      case res: AST.ResolvedInfo.Method => halt("TODO") // TODO
      case res: AST.ResolvedInfo.Var => halt("TODO") // TODO
      case res: AST.ResolvedInfo.LocalVar =>
        if (res.scope == AST.ResolvedInfo.LocalVar.Scope.Closure) {
          halt("TODO") // TODO
        }
      case _: AST.ResolvedInfo.BuiltIn => // skip
      case _: AST.ResolvedInfo.Tuple => // skip
      case _: AST.ResolvedInfo.Enum => // skip
      case _: AST.ResolvedInfo.EnumElement => // skip
      case _: AST.ResolvedInfo.Object => // skip
      case _: AST.ResolvedInfo.Package => // skip
      case _: AST.ResolvedInfo.Methods => halt("Infeasible")
    }
    return MNone()
  }

  override def preTyped(o: AST.Typed): AST.MTransformer.PreResult[AST.Typed] = {
    o match {
      case o: AST.Typed.TypeVar =>
        substMap.get(o.id) match {
          case Some(t) => AST.MTransformer.PreResult(F, MSome(t))
          case _ => halt(s"Unexpected situation when substituting type var '${o.id}'.")
        }
      case _ =>
    }
    val r = super.preTyped(o)
    return r
  }

  def addType(o: AST.Typed): Unit = {
    o match {
      case o: AST.Typed.Name =>
        val set: HashSet[AST.Typed.Name] = nameTypes.get(o.ids) match {
          case Some(s) => s
          case _ => HashSet.empty
        }
        nameTypes = nameTypes + o.ids ~> (set + o)
      case _ => otherTypes = otherTypes + o
    }
  }

  override def postTyped(o: AST.Typed): MOption[AST.Typed] = {
    addType(o)
    return MNone()
  }

  override def postTypedFun(o: AST.Typed.Fun): MOption[AST.Typed] = {
    addType(o)
    return MNone()
  }

  override def transformStmt(o: AST.Stmt): MOption[AST.Stmt] = {
    val shouldTransform: B = o match {
      case _: AST.Stmt.Match => T
      case _: AST.Stmt.While => T
      case _: AST.Stmt.For => T
      case _: AST.Stmt.If => T
      case _: AST.Stmt.Block => T
      case _: AST.Stmt.DoWhile => T
      case _: AST.Stmt.Assign => T
      case _: AST.Stmt.Expr => T
      case _: AST.Stmt.Var => T
      case _: AST.Stmt.VarPattern => T
      case _: AST.Stmt.LStmt => F
      case _: AST.Stmt.TypeAlias => F
      case _: AST.Stmt.SpecMethod => F
      case _: AST.Stmt.Object => F
      case _: AST.Stmt.Enum => F
      case _: AST.Stmt.Sig => F
      case _: AST.Stmt.AbstractDatatype => F
      case _: AST.Stmt.ExtMethod => F
      case _: AST.Stmt.Import => F
      case _: AST.Stmt.Method => F
      case _: AST.Stmt.Return => F
      case _: AST.Stmt.SpecVar => F
      case _: AST.Stmt.SubZ => F
    }
    if (shouldTransform) {
      val r = super.transformStmt(o)
      return r
    } else {
      return MNone()
    }
  }

}
