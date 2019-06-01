// #Sireum
/*
 Copyright (c) 2019, Robby, Kansas State University
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met:

 1. Redistributions of source code must retain the above copyright notice, this
    list of conditions and the following disclaimer.
 2. Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.sireum.transpilers.c

import org.sireum._
import org.sireum.message._
import org.sireum.lang.{ast => AST}
import org.sireum.lang.symbol._
import org.sireum.lang.symbol.Resolver.QName
import org.sireum.transpilers.common.TypeSpecializer
import StaticTemplate._
import org.sireum.lang.tipe.TypeChecker

object StaticTranspiler {

  type SubstMap = HashMap[String, AST.Typed]

  @datatype class ExtFile(uri: String, content: String)

  @datatype class Config(
    projectName: String,
    fprintWidth: Z,
    defaultBitWidth: Z,
    maxStringSize: Z,
    maxArraySize: Z,
    customArraySizes: HashMap[AST.Typed, Z],
    customConstants: HashMap[QName, AST.Exp],
    plugins: ISZ[Plugin],
    exts: ISZ[ExtFile],
    forLoopOpt: B,
    stackSize: String
  ) {

    @memoize def expPlugins: ISZ[ExpPlugin] = {
      var r = ISZ[ExpPlugin]()
      for (plugin <- plugins) {
        plugin match {
          case plugin: ExpPlugin => r = r :+ plugin
          case _ =>
        }
      }
      return r
    }

    @memoize def stmtPlugins: ISZ[StmtPlugin] = {
      var r = ISZ[StmtPlugin]()
      for (plugin <- plugins) {
        plugin match {
          case plugin: StmtPlugin => r = r :+ plugin
          case _ =>
        }
      }
      if (forLoopOpt) {
        r = r :+ ForLoopOptPlugin()
      }
      return r
    }

    @memoize def extMethodPlugins: ISZ[ExtMethodPlugin] = {
      var r = ISZ[ExtMethodPlugin]()
      for (plugin <- plugins) {
        plugin match {
          case plugin: ExtMethodPlugin => r = r :+ plugin
          case _ =>
        }
      }
      return r
    }

  }

  @datatype class Result(files: HashSMap[QName, ST])

  @sig trait Plugin

  @sig trait ExpPlugin extends Plugin {
    @pure def canTranspile(transpiler: StaticTranspiler, exp: AST.Exp): B

    def transpile(transpiler: StaticTranspiler, exp: AST.Exp): ST
  }

  @sig trait StmtPlugin extends Plugin {
    @pure def canTranspile(transpiler: StaticTranspiler, stmt: AST.Stmt): B

    def transpile(transpiler: StaticTranspiler, stmt: AST.Stmt): Unit
  }

  @datatype class ForLoopOptPlugin extends StmtPlugin {

    @pure def canTranspile(transpiler: StaticTranspiler, stmt: AST.Stmt): B = {
      stmt match {
        case stmt: AST.Stmt.For =>
          val eg = stmt.enumGens(0)
          eg.range match {
            case range: AST.EnumGen.Range.Step =>
              val rOptOpt = range.byOpt.map((e: AST.Exp) => transpiler.getIntConst(e))
              return (rOptOpt.isEmpty || rOptOpt.get.nonEmpty) &&
                transpiler.getIntConst(range.start).nonEmpty && transpiler.getIntConst(range.end).nonEmpty
            case _ =>
          }
        case _ =>
      }
      return F
    }

    def transpile(transpiler: StaticTranspiler, stmt: AST.Stmt): Unit = {
      val forStmt = stmt.asInstanceOf[AST.Stmt.For]
      val eg = forStmt.enumGens(0)
      val range = eg.range.asInstanceOf[AST.EnumGen.Range.Step]
      val start = transpiler.getIntConst(range.start).get
      val end = transpiler.getIntConst(range.end).get
      val byN = range.byOpt.map((e: AST.Exp) => transpiler.getIntConst(e)).getOrElse(Some(1)).get
      val posOpt = range.attr.posOpt
      val idOpt = eg.idOpt
      val body = forStmt.body
      for (i <- start to (if (range.isInclusive) end else if (start <= end) end.decrease else end.increase) by byN) {
        transpiler.stmts = transpiler.stmts ++ transpiler.transpileLoc(posOpt)
        val oldStmts = transpiler.stmts
        transpiler.stmts = ISZ()
        val bodyStmts: ISZ[AST.Stmt] = idOpt match {
          case Some(id) => val bOpt = IdConstSub(id.value, i).transformBody(body); bOpt.get.stmts
          case _ => body.stmts
        }
        for (s <- bodyStmts) {
          transpiler.transpileStmt(s)
        }
        transpiler.stmts = oldStmts :+
          st"""{
          |  ${(transpiler.stmts, "\n")}
          |}"""
      }
    }
  }

  @sig trait ExtMethodPlugin extends Plugin {

    @pure def canTranspile(transpiler: StaticTranspiler, method: TypeSpecializer.SMethod): B

    def transpile(transpiler: StaticTranspiler, compiled: Compiled, method: TypeSpecializer.SMethod): Compiled
  }

  @datatype class NumberConversionsExtMethodPlugin extends ExtMethodPlugin {

    @pure def canTranspile(transpiler: StaticTranspiler, method: TypeSpecializer.SMethod): B = {
      if (method.owner.size < 4) {
        return F
      }
      if (ops.ISZOps(method.owner).take(conversionsPkg.size) == conversionsPkg && scalarConversionObjects.contains(
          method.owner(3)
        )) {
        return T
      }
      return F
    }

    def transpile(transpiler: StaticTranspiler, compiled: Compiled, method: TypeSpecializer.SMethod): Compiled = {
      val from = ops.ISZOps(method.owner).takeRight(1)
      val id = method.id
      val sops = ops.StringOps(id)
      if (id.size > 2 && sops.startsWith("to")) {
        val to = ops.StringOps(id).substring(2, id.size)
        return compiled(
          header = compiled.header :+
            st"""static inline $to ${mangleName(method.owner)}_to$to(StackFrame sf, $from n) {
            |  return ($to) n;
            |}"""
        )
      } else {
        halt(s"TODO: $method") // TODO
      }
    }
  }

  @datatype class StringConversionsExtMethodPlugin extends ExtMethodPlugin {

    @pure def canTranspile(transpiler: StaticTranspiler, method: TypeSpecializer.SMethod): B = {
      if (method.owner.size < 4) {
        return F
      }
      if (ops.ISZOps(method.owner).take(conversionsPkg.size) == conversionsPkg && method.owner(3) == string"String") {
        return T
      }
      return F
    }

    def transpile(transpiler: StaticTranspiler, compiled: Compiled, method: TypeSpecializer.SMethod): Compiled = {
      val (header, impl): (ST, ST) = method.id.native match {
        case "toU8is" =>
          val t = transpiler.transpileType(iszU8Type)
          val h = st"void conversions_String_toU8is($t result, StackFrame caller, String s)"
          val i = st"""$h {
          |  DeclNewStackFrame(caller, "String.scala", "org.sireum.conversions.String", "toU8is", 0);
          |  size_t size = s->size * sizeof(C);
          |  sfAssert(size <= Max$t, "");
          |  result->size = (${t}SizeT) size;
          |  memcpy(result->value, s->value, size);
          |}"""
          (st"$h;", i)
        case _ => halt(s"TODO: $method") // TODO
      }
      return compiled(header = compiled.header :+ header, impl = compiled.impl :+ impl)
    }
  }

  @record class IdConstSub(id: String, const: Z) extends AST.MTransformer {
    override def postExpIdent(o: AST.Exp.Ident): MOption[AST.Exp] = {
      return if (o.id.value == id) MSome(AST.Exp.LitZ(const, AST.Attr(o.posOpt)))
      else AST.MTransformer.PostResultExpIdent
    }
  }

  @datatype class ClosureVar(context: QName, isVal: B, id: String, t: AST.Typed)

  @record class LocalClosureCollector(noTypeParam: B,
                                      var r: HashMap[QName, (B, AST.Stmt.Method, ISZ[ClosureVar])])
    extends AST.MTransformer {
    var stack: Stack[QName] = Stack.empty
    var context: QName = ISZ()
    var closureVars: ISZ[ClosureVar] = ISZ()

    override def preStmtMethod(o: AST.Stmt.Method): AST.MTransformer.PreResult[AST.Stmt] = {
      val res = o.attr.resOpt.get.asInstanceOf[AST.ResolvedInfo.Method]
      stack = stack.push(context)
      context = res.owner :+ res.id
      return AST.MTransformer.PreResultStmtMethod
    }

    override def postStmtMethod(o: AST.Stmt.Method): MOption[AST.Stmt] = {
      val res = o.attr.resOpt.get.asInstanceOf[AST.ResolvedInfo.Method]
      r = r + (res.owner :+ res.id) ~> ((noTypeParam, o, closureVars))

      val Some((c, s)) = stack.pop
      context = c
      stack = s
      var cvs = ISZ[ClosureVar]()
      for (cv <- closureVars) {
        if (cv.context != c) {
          cvs = cvs :+ cv
        }
      }
      closureVars = cvs
      return AST.MTransformer.PostResultStmtMethod
    }

    override def postResolvedAttr(o: AST.ResolvedAttr): MOption[AST.ResolvedAttr] = {
      o.resOpt.get match {
        case res: AST.ResolvedInfo.LocalVar if res.scope == AST.ResolvedInfo.LocalVar.Scope.Closure =>
          var found = F
          for (cv2 <- closureVars if cv2.id == res.id) {
            found = T
          }
          if (!found) {
            closureVars = closureVars :+ ClosureVar(res.context, res.isVal, res.id, o.typedOpt.get)
          }
        case _ =>
      }
      return AST.MTransformer.PostResultResolvedAttr
    }
  }

  val transKind: String = "Static C Transpiler"

  val builtInTypes: HashSet[AST.Typed] = HashSet ++ ISZ[AST.Typed](
    AST.Typed.unit,
    AST.Typed.b,
    AST.Typed.c,
    AST.Typed.z,
    AST.Typed.f32,
    AST.Typed.f64,
    AST.Typed.r,
    AST.Typed.string
  )

  val escapeSep: String = """" """"
  val iszStringType: AST.Typed.Name = AST.Typed.Name(AST.Typed.isName, ISZ(AST.Typed.z, AST.Typed.string))
  val iszU8Type: AST.Typed.Name = AST.Typed.Name(AST.Typed.isName, ISZ(AST.Typed.z, AST.Typed.u8))
  val optionName: QName = AST.Typed.optionName
  val someName: QName = AST.Typed.someName
  val noneName: QName = AST.Typed.noneName
  val eitherName: QName = AST.Typed.sireumName :+ "Either"
  val moptionName: QName = AST.Typed.sireumName :+ "MOption"
  val meitherName: QName = AST.Typed.sireumName :+ "MEither"

  val sNameSet: HashSet[QName] = HashSet ++ ISZ[QName](
    AST.Typed.isName,
    AST.Typed.iszName,
    AST.Typed.msName,
    AST.Typed.mszName,
    AST.Typed.zsName
  )

  val conversionsPkg: ISZ[String] = AST.Typed.sireumName :+ "conversions"

  val scalarConversionObjects: HashSet[String] = HashSet ++ ISZ(
    "Z",
    "Z8",
    "Z16",
    "Z32",
    "Z64",
    "N",
    "N8",
    "N16",
    "N32",
    "N64",
    "S8",
    "S16",
    "S32",
    "S64",
    "U8",
    "U16",
    "U32",
    "U64"
  )
}

import StaticTranspiler._

@record class StaticTranspiler(config: Config, ts: TypeSpecializer.Result) {
  val reporter: Reporter = Reporter.create
  var compiledMap: HashMap[QName, Compiled] = HashMap.empty
  var typeNameMap: HashMap[AST.Typed, ST] = HashMap.empty
  var mangledTypeNameMap: HashMap[String, AST.Typed] = HashMap.empty
  var allHEntries: ISZ[ST] = ISZ()
  var allCEntries: ISZ[ST] = ISZ()
  var bConstructor: B = F
  var zConstructor: B = F
  var f32Constructor: B = F
  var f64Constructor: B = F
  var rConstructor: B = F

  var context: QName = ISZ()
  var currReceiverOpt: Option[AST.Typed.Name] = None()
  var stmts: ISZ[ST] = ISZ()
  var nextTempNum: Z = 0
  var localRename: HashMap[String, ST] = HashMap.empty
  var nestedMethods: HashMap[QName, (B, AST.Stmt.Method, ISZ[ClosureVar])] = HashMap.empty

  def transpile(rep: Reporter): Result = {

    var r = HashSMap.empty[QName, ST]
    var cFilenames: ISZ[ISZ[String]] = ISZ()

    def transEntryPoints(): Unit = {
      var i = 0
      for (ep <- ts.entryPoints) {
        ep match {
          case ep: TypeSpecializer.EntryPoint.Worksheet =>
            val (exeName, main) = transpileWorksheet(ep.program, i)
            val cFilename = s"$exeName.c"
            r = r + ISZ[String](cFilename) ~> main
            cFilenames = cFilenames :+ ISZ(cFilename)
            i = i + 1
          case ep: TypeSpecializer.EntryPoint.App =>
            val m = ts.typeHierarchy.nameMap.get(ep.name :+ "main").get.asInstanceOf[Info.Method]
            val atExitOpt: Option[Info.Method] = ts.typeHierarchy.nameMap.get(ep.name :+ "atExit") match {
              case Some(info: Info.Method) => Some(info)
              case _ => None()
            }
            val (exeName, main) = transpileMain(m, atExitOpt, i)
            val cFilename = s"$exeName.c"
            val cPath = ISZ[String](s"app-$exeName", cFilename)
            r = r + cPath ~> main
            cFilenames = cFilenames :+ cPath
            i = i + 1
        }
      }
      rep.reports(reporter.messages)
    }

    def transpileObjectVars(name: QName, vars: HashSSet[String]): Unit = {
      val oldCurrReceiverOpt = currReceiverOpt
      val oldStmts = stmts
      val oldNextTempNum = nextTempNum
      val value = getCompiled(name)
      val oInfo = ts.typeHierarchy.nameMap.get(name).get.asInstanceOf[Info.Object]
      var vs = ISZ[(TypeKind.Type, String, ST, ST, B)]()
      stmts = ISZ()
      val mangledName = mangleName(name)
      for (stmt <- oInfo.ast.stmts) {
        stmt match {
          case stmt: AST.Stmt.Var if vars.contains(stmt.id.value) =>
            val id = stmt.id.value
            val t = stmt.tipeOpt.get.typedOpt.get
            val kind = typeKind(t)
            vs = vs :+ ((kind, id, typeDecl(t), transpileType(t), !stmt.isVal))
            val init: AST.AssignExp = config.customConstants.get(oInfo.name :+ id) match {
              case Some(e) => AST.Stmt.Expr(e, AST.TypedAttr(e.posOpt, e.typedOpt))
              case _ => stmt.initOpt.get
            }
            transpileAssignExp(
              init,
              (rhs, rhsT) =>
                if (isScalar(kind)) st"_${mangledName}_$id = $rhs;"
                else st"Type_assign(&_${mangledName}_$id, $rhs, sizeof($rhsT));"
            )
          case _ =>
        }
      }
      val uri = filenameOfPosOpt(oInfo.ast.posOpt, "")
      val newValue = obj(value, uri, name, mangledName, vs, stmts)
      currReceiverOpt = oldCurrReceiverOpt
      stmts = oldStmts
      nextTempNum = oldNextTempNum
      compiledMap = compiledMap + name ~> newValue
    }

    def genFiles(): Unit = {

      val runtimeDir = string"runtime"
      val files = Runtime.staticFiles
      for (e <- files.entries) {
        val k = e._1
        if (k.size == z"1") {
          r = r + ISZ(runtimeDir, k(0)) ~> st"${e._2}"
        }
      }
      val ztypeFilename = string"ztype.h"
      val ztype: ST = config.defaultBitWidth match {
        case z"8" => st"${files.get(ISZ("8", ztypeFilename)).get}"
        case z"16" => st"${files.get(ISZ("16", ztypeFilename)).get}"
        case z"32" => st"${files.get(ISZ("32", ztypeFilename)).get}"
        case z"64" => st"${files.get(ISZ("64", ztypeFilename)).get}"
        case _ => halt("Infeasible")
      }
      r = r + ISZ(runtimeDir, ztypeFilename) ~> ztype

      val typeNames: ISZ[(String, ST, ST)] = {
        var tn: ISZ[(String, ST, ST)] = ISZ(
          (dotName(AST.Typed.stringName), st"${mangleName(AST.Typed.stringName)}", st"String")
        )
        for (e <- typeNameMap.entries) {
          if (!builtInTypes.contains(e._1) && isClass(typeKind(e._1))) {
            val s = e._1.string
            tn = tn :+ ((s, e._2, escapeString(None(), s)))
          }
        }
        ops.ISZOps(tn).sortWith((p1, p2) => p1._1 <= p2._1)
      }

      val typeQNames = compiledMap.keys
      r = r + ISZ[String](runtimeDir, "type-composite.h") ~> typeCompositeH(
        config.maxStringSize,
        minIndexMaxElementSize(iszStringType)._2,
        typeNames
      )
      r = r + ISZ[String](runtimeDir, "types.h") ~> typesH(typeQNames, typeNames)
      r = r + ISZ[String](runtimeDir, "types.c") ~> typesC(typeNames)
      r = r + ISZ[String](runtimeDir, "all.h") ~> allH(typeQNames, allHEntries)
      r = r + ISZ[String](runtimeDir, "all.c") ~> allC(typeNames, allCEntries)
      r = r ++ compiled(compiledMap)
      for (ext <- config.exts) {
        r = r + ISZ[String]("ext", filename(Some(ext.uri), "")) ~> st"${ext.content}"
      }
      r = r + ISZ[String]("CMakeLists.txt") ~> cmake(config.projectName, config.stackSize, cFilenames, r.keys)
      r = r + ISZ[String]("typemap.properties") ~> typeManglingMap(
        for (e <- mangledTypeNameMap.entries) yield (e._1, e._2.string)
      )
    }

    def work(): Unit = {
      genTypeNames()
      for (ms <- ts.methods.values; m <- ms.elements) {
        transpileMethod(m)
      }
      for (p <- ts.objectVars.entries) {
        transpileObjectVars(p._1, p._2)
      }
      for (m <- ts.traitMethods.elements) {
        transpileTraitMethod(m)
      }
      for (m <- ts.extMethods.elements if !sNameSet.contains(m.owner)) {
        var found = F
        val name = m.owner
        val value = getCompiled(name)
        for (p <- config.extMethodPlugins) {
          if (p.canTranspile(this, m)) {
            found = T
            val newValue = p.transpile(this, value, m)
            compiledMap = compiledMap + name ~> newValue
          }
        }
        if (!found) {
          reporter.info(None(), transKind, st"Generated @ext method header for '${(m.owner :+ m.id, ".")}'.".render)
          val info = ts.typeHierarchy.nameMap.get(m.owner :+ m.id).get.asInstanceOf[Info.ExtMethod]
          val mh = methodHeader(
            m.receiverOpt,
            T,
            info.owner,
            info.ast.sig.id.value,
            info.ast.sig.typeParams.isEmpty,
            m.tpe,
            info.ast.sig.params.map((p: AST.Param) => p.id.value),
            ISZ()
          )
          compiledMap = compiledMap + name ~> value(header = value.header :+ st"$mh;")
        }
      }
      transEntryPoints()
      genFiles()
    }

    work()

    return Result(r)
  }

  def genClassConstructor(nt: TypeSpecializer.NamedType): Unit = {
    val t = nt.tpe
    val key = compiledKeyName(t)
    val value = getCompiled(key)
    var types = ISZ[AST.Typed]()
    var cps = ISZ[(TypeKind.Type, String, ST, ST)]()
    for (cv <- nt.constructorVars.entries) {
      val (id, (_, _, ct)) = cv
      types = types :+ ct
      val tpe = fingerprint(ct)._1
      val kind = typeKind(ct)
      cps = cps :+ ((kind, fieldId(id).render, typeDecl(ct), tpe))
    }
    val oldNextTempNum = nextTempNum
    val oldStmts = stmts
    val oldReceiverOpts = currReceiverOpt
    currReceiverOpt = Some(t)
    nextTempNum = 0
    stmts = ISZ()

    // TODO: other stmts
    for (v <- nt.vars.entries) {
      val (id, (_, ct, init)) = v
      val kind = typeKind(ct)
      if (isScalar(kind)) {
        transpileAssignExp(init, (rhs, _) => st"this->$id = $rhs;")
      } else {
        transpileAssignExp(init, (rhs, rhsT) => st"Type_assign(&this->$id, $rhs, sizeof($rhsT));")
      }
    }

    val uri =
      filenameOfPosOpt(ts.typeHierarchy.typeMap.get(t.ids).get.asInstanceOf[TypeInfo.Adt].posOpt, "")
    val newValue = claszConstructor(value, uri, key, fingerprint(t)._1, cps, stmts)
    compiledMap = compiledMap + key ~> newValue

    nextTempNum = oldNextTempNum
    stmts = oldStmts
    currReceiverOpt = oldReceiverOpts
  }

  def transpileAssignExp(exp: AST.AssignExp, f: (ST, ST) => ST @pure): Unit = {
    exp match {
      case exp: AST.Stmt.Expr =>
        val rhs = transpileExp(exp.exp)
        stmts = stmts :+ f(rhs, typeDecl(exp.typedOpt.get))
      case exp: AST.Stmt.Block =>
        val bstmts = exp.body.stmts
        for (stmt <- ops.ISZOps(bstmts).dropRight(1)) {
          transpileStmt(stmt)
        }
        transpileAssignExp(bstmts(bstmts.size - 1).asAssignExp, f)
      case exp: AST.Stmt.If => transpileIf(exp, Some(f))
      case exp: AST.Stmt.Match => transpileMatch(exp, Some(f))
      case exp: AST.Stmt.Return => transpileStmt(exp)
    }
  }

  def classVars(
    nt: TypeSpecializer.NamedType
  ): (ISZ[AST.Typed], ISZ[(TypeKind.Type, String, ST, ST, B)], ISZ[(TypeKind.Type, String, ST, ST, B)]) = {
    var types = ISZ[AST.Typed]()
    var cps = ISZ[(TypeKind.Type, String, ST, ST, B)]()
    for (cv <- nt.constructorVars.entries) {
      val (id, (isVal, _, ct)) = cv
      types = types :+ ct
      val tpe = fingerprint(ct)._1
      val kind = typeKind(ct)
      cps = cps :+ ((kind, id, st"${typePrefix(kind)} $tpe", tpe, !isVal))
    }
    var vs = ISZ[(TypeKind.Type, String, ST, ST, B)]()
    for (v <- nt.vars.entries) {
      val (id, (isVal, ct, _)) = v
      types = types :+ ct
      val tpe = fingerprint(ct)._1
      val kind = typeKind(ct)
      vs = vs :+ ((kind, id, st"${typePrefix(kind)} $tpe", tpe, !isVal))
    }
    return (types, cps, vs)
  }

  @pure def minIndexMaxElementSize(t: AST.Typed.Name): (Z, Z) = {
    val indexType = t.args(0)
    val size: Z = config.customArraySizes.get(t) match {
      case Some(n) => n
      case _ => config.maxArraySize
    }
    if (indexType == AST.Typed.z) {
      return (z"0", size)
    }
    val ast =
      ts.typeHierarchy.typeMap.get(indexType.asInstanceOf[AST.Typed.Name].ids).get.asInstanceOf[TypeInfo.SubZ].ast
    if (ast.isZeroIndex) {
      if (ast.hasMin && ast.hasMax) {
        val d = ast.max + 1
        return (z"0", if (d < size) d else size)
      } else {
        return (z"0", size)
      }
    } else {
      if (ast.hasMax) {
        val d = ast.max + -ast.min + 1
        return (ast.min, if (d < size) d else size)
      } else {
        return (ast.min, size)
      }
    }
  }

  def getCompiled(key: QName): Compiled = {
    compiledMap.get(key) match {
      case Some(r) => return r
      case _ => return Compiled(ISZ(), ISZ(), ISZ())
    }
  }

  @pure def fprint(t: AST.Typed): ST = {
    val width = config.fprintWidth
    val max: Z = if (0 < width && width <= 64) width else 64
    val bytes = ops.ISZOps(crypto.SHA3.sum512(conversions.String.toU8is(t.string))).take(max)
    var cs = ISZ[C]()
    for (b <- bytes) {
      val c = conversions.U32.toC(conversions.U8.toU32(b))
      cs = cs :+ ops.COps.hex2c((c >>> '\u0004') & '\u000F')
      cs = cs :+ ops.COps.hex2c(c & '\u000F')
    }
    return st"$cs"
  }

  @pure def fingerprint(t: AST.Typed): (ST, B) = {
    t match {
      case t: AST.Typed.Name =>
        ts.typeHierarchy.typeMap.get(t.ids).get match {
          case _: TypeInfo.Enum => return (mangleName(ops.ISZOps(t.ids).dropRight(1)), F)
          case _ => return if (t.args.isEmpty) (mangleName(t.ids), F) else (st"${mangleName(t.ids)}_${fprint(t)}", T)
        }
      case t: AST.Typed.Tuple => return (st"Tuple${t.args.size}_${fprint(t)}", T)
      case t: AST.Typed.Fun => return (st"Fun${t.args.size}_${fprint(t)}", T)
      case _ => halt(s"Infeasible: $t")
    }
  }

  def genTypeNames(): Unit = {
    @pure def typeFilename(t: AST.Typed): Option[QName] = {
      val tname: QName = t match {
        case t: AST.Typed.Name =>
          ts.typeHierarchy.typeMap.get(t.ids).get match {
            case _: TypeInfo.Enum => ops.ISZOps(t.ids).dropRight(1)
            case _ => compiledKeyName(t)
          }
        case t: AST.Typed.Tuple => compiledKeyName(t)
        case t: AST.Typed.Fun => compiledKeyName(t)
        case _ => halt("Infeasible")
      }
      return if (tname.size == z"1") None() else Some(tname)
    }

    @pure def includes(ts: ISZ[AST.Typed]): ISZ[ST] = {
      var r = ISZ[ST]()
      for (t <- ts) {
        if (!builtInTypes.contains(t)) {
          typeFilename(t) match {
            case Some(n) => r = r :+ st"#include <${typeHeaderFilename(filenameOf(n))}>"
            case _ =>
          }
        }
      }
      return r
    }

    def genArray(t: AST.Typed.Name): Unit = {
      val key = AST.Typed.sireumName :+ fingerprint(t)._1.render
      val it = t.args(0)
      val et = t.args(1)
      val etKind = typeKind(et)
      val indexType = genType(it)
      val elementType = typeDecl(et)
      genType(et)
      val value = getCompiled(key)
      val (minIndex, maxElementSize) = minIndexMaxElementSize(t)
      val otherType: AST.Typed.Name =
        if (t.ids == AST.Typed.isName) AST.Typed.Name(AST.Typed.msName, ISZ(it, et))
        else AST.Typed.Name(AST.Typed.isName, ISZ(it, et))
      val otherTpeOpt: Option[ST] = ts.nameTypes.get(otherType.ids) match {
        case Some(s) if s.contains(TypeSpecializer.NamedType(otherType, Map.empty, Map.empty)) =>
          Some(fingerprint(otherType)._1)
        case _ => None()
      }
      val newValue = array(
        value,
        includes(ISZ(it, et)),
        t.string,
        t.ids == AST.Typed.isName,
        fingerprint(t)._1,
        otherTpeOpt,
        indexType,
        minIndex,
        isScalar(etKind),
        elementType,
        transpileType(et),
        maxElementSize
      )
      compiledMap = compiledMap + key ~> newValue
    }
    def genEnum(t: AST.Typed.Name): ST = {
      val name = ops.ISZOps(t.ids).dropRight(1)
      val mangledName = mangleName(name)
      typeNameMap = typeNameMap + t ~> mangledName
      val info = ts.typeHierarchy.nameMap.get(name).get.asInstanceOf[Info.Enum]
      val elements = info.elements.keys
      val elementType = info.elementTypedOpt.get
      val optionElementType = AST.Typed.Name(optionName, ISZ(elementType))
      val optElementTypeOpt: Option[(ST, ST, ST)] = ts.nameTypes.get(optionName) match {
        case Some(s) if s.contains(TypeSpecializer.NamedType(optionElementType, Map.empty, Map.empty)) =>
          val someElementType = AST.Typed.Name(someName, ISZ(elementType))
          val noneElementType = AST.Typed.Name(noneName, ISZ(elementType))
          genType(someElementType)
          genType(noneElementType)
          Some((fingerprint(optionElementType)._1, fingerprint(someElementType)._1, fingerprint(noneElementType)._1))
        case _ => None()
      }
      val iszElementType = AST.Typed.Name(AST.Typed.isName, ISZ(AST.Typed.z, elementType))
      val iszElementTypeOpt: Option[ST] = ts.nameTypes.get(AST.Typed.isName) match {
        case Some(s) if s.contains(TypeSpecializer.NamedType(iszElementType, Map.empty, Map.empty)) =>
          Some(fingerprint(iszElementType)._1)
        case _ => None()
      }
      val value = getCompiled(name)
      val newValue =
        enum(value, filenameOfPosOpt(info.posOpt, ""), name, elements, optElementTypeOpt, iszElementTypeOpt)
      compiledMap = compiledMap + name ~> newValue
      return mangledName
    }
    def genSubZ(t: AST.Typed.Name): Unit = {
      val name = t.ids
      val mangledName = mangleName(name)
      typeNameMap = typeNameMap + t ~> mangledName
      val info = ts.typeHierarchy.typeMap.get(name).get.asInstanceOf[TypeInfo.SubZ]
      val optionType = AST.Typed.Name(optionName, ISZ(t))
      val optionTypeOpt: Option[(ST, ST, ST)] = ts.nameTypes.get(optionName) match {
        case Some(s) if s.contains(TypeSpecializer.NamedType(optionType, Map.empty, Map.empty)) =>
          val someType = AST.Typed.Name(someName, ISZ(t))
          val noneType = AST.Typed.Name(noneName, ISZ(t))
          Some((fingerprint(optionType)._1, fingerprint(someType)._1, fingerprint(noneType)._1))
        case _ => None()
      }
      val value = getCompiled(name)
      val ast = info.ast
      val bw = ast.bitWidth
      val newValue = subz(
        value,
        filenameOfPosOpt(info.posOpt, ""),
        name,
        if (bw == z"0") config.defaultBitWidth else bw,
        ast.isBitVector,
        !ast.isSigned,
        if (ast.hasMin) Some(ast.min) else None(),
        if (ast.hasMax) Some(ast.max) else None(),
        optionTypeOpt
      )
      compiledMap = compiledMap + name ~> newValue
    }
    def genTuple(t: AST.Typed.Tuple): Unit = {
      val key = compiledKeyName(t)
      val value = getCompiled(key)
      var paramTypes = ISZ[(TypeKind.Type, ST, ST)]()
      for (arg <- t.args) {
        genType(arg)
        val tPtr = fingerprint(arg)._1
        val kind = typeKind(arg)
        paramTypes = paramTypes :+ ((kind, typeDecl(arg), tPtr))
      }
      val newValue = tuple(value, t.string, includes(t.args), fingerprint(t)._1, paramTypes)
      compiledMap = compiledMap + key ~> newValue
    }
    def genClass(t: AST.Typed.Name): Unit = {
      val key = compiledKeyName(t)
      val value = getCompiled(key)
      for (nt <- ts.nameTypes.get(t.ids).get.elements if nt.tpe == t) {
        var types = ISZ[AST.Typed]()
        var cps = ISZ[Vard]()
        for (cv <- nt.constructorVars.entries) {
          val (id, (isVal, isHidden, ct)) = cv
          types = types :+ ct
          val tpe = genType(ct)
          val kind = typeKind(ct)
          cps = cps :+ Vard(kind, fieldId(id).render, typeDecl(ct), tpe, !isVal, isHidden)
        }
        var vs = ISZ[Vard]()
        for (v <- nt.vars.entries) {
          val (id, (isVal, ct, _)) = v
          types = types :+ ct
          val tpe = genType(ct)
          val kind = typeKind(ct)
          vs = vs :+ Vard(kind, fieldId(id).render, typeDecl(ct), tpe, !isVal, F)
        }
        val uri =
          filenameOfPosOpt(ts.typeHierarchy.typeMap.get(t.ids).get.asInstanceOf[TypeInfo.Adt].posOpt, "")
        val newValue = clasz(value, uri, t.ids, includes(types), t.string, fingerprint(t)._1, cps, vs)
        compiledMap = compiledMap + key ~> newValue
      }
    }
    def genTrait(t: AST.Typed.Name): Unit = {
      var leafTypes = ISZ[ST]()
      val tImpls = ts.typeImpl.childrenOf(t).elements
      for (tImpl <- tImpls) {
        val tST = genType(tImpl)
        leafTypes = leafTypes :+ tST
      }
      val key = compiledKeyName(t)
      val value = getCompiled(key)
      val newValue = traitz(value, fingerprint(t)._1, t.string, includes(tImpls.map(t => t)), leafTypes)
      compiledMap = compiledMap + key ~> newValue
    }
    def genFun(tf: AST.Typed.Fun): Unit = {
      halt(s"TODO: $tf") // TODO
    }
    def genType(t: AST.Typed): ST = {
      typeNameMap.get(t) match {
        case Some(tr) => return tr
        case _ =>
      }
      t match {
        case t: AST.Typed.Name =>
          if (!builtInTypes.contains(t)) {
            typeKind(t) match {
              case TypeKind.Immutable => genClass(t)
              case TypeKind.ImmutableTrait => genTrait(t)
              case TypeKind.Mutable => genClass(t)
              case TypeKind.MutableTrait => genTrait(t)
              case TypeKind.IS => genArray(t)
              case TypeKind.MS => genArray(t)
              case TypeKind.Enum => val r = genEnum(t); return r
              case _ => genSubZ(t)
            }
          }
        case t: AST.Typed.Tuple =>
          genTuple(t)
        case t: AST.Typed.Fun =>
          genFun(t)
        case _ => halt("Infeasible: $t")
      }
      val p = fingerprint(t)
      if (p._2) {
        val tString = p._1.render
        mangledTypeNameMap.get(tString) match {
          case Some(t2) =>
            if (t != t2) {
              reporter.error(
                None(),
                transKind,
                st"Type name mangling collision is detected for '$t2' and '$t}' as '$tString' as (please increase fingerprint width).".render
              )
            }
          case _ => mangledTypeNameMap = mangledTypeNameMap + tString ~> t
        }
      }
      typeNameMap = typeNameMap + t ~> p._1
      return p._1
    }

    genType(AST.Typed.string)

    for (nts <- ts.nameTypes.values; nt <- nts.elements) {
      ts.typeHierarchy.typeMap.get(nt.tpe.ids).get match {
        case _: TypeInfo.Adt => genType(nt.tpe)
        case _: TypeInfo.Sig => genType(nt.tpe)
        case _: TypeInfo.Enum => genType(nt.tpe)
        case _: TypeInfo.SubZ => genType(nt.tpe)
        case _ =>
      }
    }
    for (t <- ts.otherTypes.elements) {
      genType(t)
    }
    for (nts <- ts.nameTypes.values; nt <- nts.elements) {
      ts.typeHierarchy.typeMap.get(nt.tpe.ids).get match {
        case info: TypeInfo.Adt if !info.ast.isRoot => genClassConstructor(nt)
        case _ =>
      }
    }
  }

  def transpileMain(m: Info.Method, atExitOpt: Option[Info.Method], i: Z): (String, ST) = {
    val fileUriOpt: Option[String] = m.ast.posOpt match {
      case Some(pos) => pos.uriOpt
      case _ => None()
    }
    val fname = filename(fileUriOpt, "main")
    val atExit: ISZ[ST] = atExitOpt match {
      case Some(atexit) =>
        val oldStmts = stmts
        val e =
          transObjectMethodInvoke(ISZ(), AST.Typed.unit, methodNameRes(None(), atexit.methodRes), ISZ(), ISZ())
        val r = ISZ(st"StackFrame sf = NULL;", st"$e;")
        stmts = oldStmts
        r
      case _ => ISZ()
    }
    return (
      if (fname != string"main") removeExt(fname) else if (i == z"0") "main" else s"main$i",
      main(
        fname,
        m.owner,
        m.ast.sig.id.value,
        transpileType(iszStringType),
        arraySizeType(minIndexMaxElementSize(iszStringType)._2),
        atExit
      )
    )
  }

  def transpileWorksheet(program: AST.TopUnit.Program, i: Z): (String, ST) = {
    val fname = filename(program.fileUriOpt, "main")
    val exeName = removeExt(fname)
    stmts = ISZ()
    nextTempNum = 0
    assert(program.packageName.ids.isEmpty)
    for (stmt <- program.body.stmts) {
      transpileStmt(stmt)
    }
    return (if (i == z"0") exeName else if (exeName == string"main") s"main$i" else exeName, worksheet(fname, stmts))
  }

  def transpileTraitMethod(method: TypeSpecializer.SMethod): Unit = {
    val id = method.id
    def findMethod(receiver: AST.Typed.Name): Info.Method = {
      val adtInfo = ts.typeHierarchy.typeMap.get(receiver.ids).get.asInstanceOf[TypeInfo.Adt]
      val adtSm =
        TypeChecker.buildTypeSubstMap(receiver.ids, None(), adtInfo.ast.typeParams, receiver.args, reporter).get
      val mInfo = adtInfo.methods.get(id).get
      val mt = mInfo.methodType.tpe.subst(adtSm)
      val rep = Reporter.create
      val th = ts.typeHierarchy
      for (m <- ts.methods.get(receiver.ids).get.elements if m.info.ast.sig.id.value == id) {
        val smOpt = TypeChecker.unify(th, None(), TypeChecker.TypeRelation.Equal, m.info.methodType.tpe, mt, rep)
        if (smOpt.nonEmpty) {
          return m.info
        }
      }
      //return mInfo
      halt(s"Infeasible: $method of $receiver}")
    }
    val receiver = method.receiverOpt.get
    val info: Info.Method = ts.typeHierarchy.typeMap.get(receiver.ids).get match {
      case inf: TypeInfo.Sig => inf.methods.get(method.id).get
      case inf: TypeInfo.Adt => inf.methods.get(method.id).get
      case _ => halt("Infeasible")
    }
    val key = compiledKeyName(receiver)
    val value = getCompiled(key)
    val res = info.resOpt.get.asInstanceOf[AST.ResolvedInfo.Method]
    val header = methodHeaderRes(method.receiverOpt, res(tpeOpt = Some(method.tpe)))
    var cases = ISZ[ST]()
    val rt = method.tpe.ret
    val rTpe = typeDecl(rt)
    val scalar = isScalar(typeKind(rt))
    for (t <- ts.typeImpl.childrenOf(receiver).elements) {
      val adt = ts.typeHierarchy.typeMap.get(t.ids).get.asInstanceOf[TypeInfo.Adt]
      val tpe = transpileType(t)
      adt.vars.get(id) match {
        case Some(_) =>
          if (scalar) {
            cases = cases :+ st"case T$tpe: return ${tpe}_${id}_(($tpe) this);"
          } else {
            cases = cases :+ st"case T$tpe: Type_assign(result, ${tpe}_${id}_(($tpe) this), sizeof($rTpe)); return;"
          }
        case _ =>
          val minfo = findMethod(t)
          val mres = minfo.methodRes
          val mName = methodNameRes(Some(t), mres)
          val args: ST =
            if (mres.paramNames.isEmpty) st""
            else
              st", ${(for (p <- ops.ISZOps(mres.paramNames).zip(minfo.methodType.tpe.args)) yield st"(${transpileType(p._2)}) ${localId(p._1)}", ", ")}"
          if (rt == AST.Typed.unit) {
            cases = cases :+ st"case T$tpe: $mName(caller, ($tpe) this$args); return;"
          } else if (scalar) {
            cases = cases :+ st"case T$tpe: return $mName(caller, ($tpe) this$args);"
          } else {
            cases = cases :+ st"case T$tpe: $mName(result, caller, ($tpe) this$args); return;"
          }
      }
    }
    val impl =
      st"""$header {
      |  switch (this->type) {
      |    ${(cases, "\n")}
      |    default: fprintf(stderr, "Infeasible TYPE: %s.\n", TYPE_string(this)); exit(1);
      |  }
      |}"""
    val newValue = value(header = value.header :+ st"$header;", impl = value.impl :+ impl)
    compiledMap = compiledMap + key ~> newValue
  }

  def buildNestedMethodInfo(
    noTypeParam: B,
    method: TypeSpecializer.Method
  ): HashMap[QName, (B, AST.Stmt.Method, ISZ[ClosureVar])] = {
    val collector = LocalClosureCollector(noTypeParam, HashMap.empty)
    for (stmt <- method.info.ast.bodyOpt.get.stmts) {
      collector.transformStmt(stmt)
    }
    return collector.r
  }

  def transpileMethod(method: TypeSpecializer.Method): Unit = {
    val res = method.info.methodRes
    val key: QName = currReceiverOpt match {
      case Some(rcv) => compiledKeyName(rcv)
      case _ =>
        val owner = res.owner
        if (owner.isEmpty) {
          ISZ("top")
        } else {
          owner
        }
    }

    val oldContext = context
    val oldNextTempNum = nextTempNum
    val oldStmts = stmts
    val oldCurrReceiverOpt = currReceiverOpt

    nestedMethods = buildNestedMethodInfo(method.info.ast.sig.typeParams.isEmpty, method)
    for (p <- nestedMethods.entries) {
      val (c, (noTypeParam, m, cls)) = p
      context = c
      val mres = m.attr.resOpt.get.asInstanceOf[AST.ResolvedInfo.Method]
      val header = methodHeader(
        method.receiverOpt,
        F,
        mres.owner,
        mres.id,
        noTypeParam,
        m.sig.funType,
        m.sig.params.map(p => p.id.value),
        cls
      )

      currReceiverOpt = method.receiverOpt
      nextTempNum = 0
      stmts = ISZ()

      for (stmt <- m.bodyOpt.get.stmts) {
        transpileStmt(stmt)
      }

      val impl =
        st"""$header {
        |  DeclNewStackFrame(caller, "${filenameOfPosOpt(m.posOpt, "")}", "${dotName(mres.owner)}", "${ mres.id}", 0);
        |  ${(stmts, "\n")}
        |}"""

      val value = getCompiled(key)
      compiledMap = compiledMap + key ~> value(header = value.header :+ st"$header;", impl = value.impl :+ impl)
    }

    context = res.owner :+ res.id
    currReceiverOpt = method.receiverOpt
    nextTempNum = 0
    stmts = ISZ()

    val header = methodHeaderRes(method.receiverOpt, res)
    for (stmt <- method.info.ast.bodyOpt.get.stmts) {
      transpileStmt(stmt)
    }
    val impl =
      st"""$header {
      |  DeclNewStackFrame(caller, "${filenameOfPosOpt(method.info.ast.posOpt, "")}", "${dotName(res.owner)}", "${res.id}", 0);
      |  ${(stmts, "\n")}
      |}"""

    val value = getCompiled(key)
    compiledMap = compiledMap + key ~> value(header = value.header :+ st"$header;", impl = value.impl :+ impl)

    currReceiverOpt = oldCurrReceiverOpt
    nextTempNum = oldNextTempNum
    stmts = oldStmts
    nestedMethods = HashMap.empty
    context = oldContext
  }

  def checkBitWidth(posOpt: Option[Position], n: Z, bitWidth: Z, isUnsigned: B): Unit = {
    var ok = T
    val bw: Z = if (bitWidth == z"0") config.defaultBitWidth else bitWidth
    bw match {
      case z"8" =>
        if (isUnsigned) {
          ok = 0 <= n && n <= u8Max
        } else {
          ok = i8Min <= n && n <= i8Max
        }
      case z"16" =>
        if (isUnsigned) {
          ok = 0 <= n && n <= u16Max
        } else {
          ok = i16Min <= n && n <= i16Max
        }
      case z"32" =>
        if (isUnsigned) {
          ok = 0 <= n && n <= u32Max
        } else {
          ok = i32Min <= n && n <= i32Max
        }
      case z"64" =>
        if (isUnsigned) {
          ok = 0 <= n && n <= u64Max
        } else {
          ok = i64Min <= n && n <= i64Max
        }
      case _ => halt("Infeasible")
    }
    if (!ok) {
      reporter.error(posOpt, transKind, s"Invalid ${config.defaultBitWidth}-bit Z literal '$n'.")
    }
  }

  def transpileLitC(posOpt: Option[Position], c: C): ST = {
    val ec = escapeChar(posOpt, c)
    return st"'$ec'"
  }

  def transpileLitZ(posOpt: Option[Position], n: Z): ST = {
    checkBitWidth(posOpt, n, config.defaultBitWidth, F)
    return st"Z_C($n)"
  }

  @pure def transpileLitF32(n: F32): ST = {
    return st"${n.string}F"
  }

  @pure def transpileLitF64(n: F64): ST = {
    return st"${n.string}"
  }

  @pure def transpileLitR(n: R): ST = {
    return st"${n.string}L"
  }

  def transpileLitString(posOpt: Option[Position], s: String): ST = {
    val value = escapeString(posOpt, s)
    return st"""string("$value")"""
  }

  def transpileLit(lit: AST.Lit): ST = {
    @pure def transLitB(exp: AST.Exp.LitB): ST = {
      return if (exp.value) trueLit else falseLit
    }

    def transLitC(exp: AST.Exp.LitC): ST = {
      val r = transpileLitC(exp.posOpt, exp.value)
      return r
    }

    def transLitZ(exp: AST.Exp.LitZ): ST = {
      val r = transpileLitZ(exp.posOpt, exp.value)
      return r
    }

    @pure def transLitF32(exp: AST.Exp.LitF32): ST = {
      return transpileLitF32(exp.value)
    }

    @pure def transLitF64(exp: AST.Exp.LitF64): ST = {
      return transpileLitF64(exp.value)
    }

    @pure def transLitR(exp: AST.Exp.LitR): ST = {
      return transpileLitR(exp.value)
    }

    def transLitString(exp: AST.Exp.LitString): ST = {
      val r = transpileLitString(exp.posOpt, exp.value)
      return r
    }

    lit match {
      case lit: AST.Exp.LitB => val r = transLitB(lit); return r
      case lit: AST.Exp.LitC => val r = transLitC(lit); return r
      case lit: AST.Exp.LitZ => val r = transLitZ(lit); return r
      case lit: AST.Exp.LitF32 => val r = transLitF32(lit); return r
      case lit: AST.Exp.LitF64 => val r = transLitF64(lit); return r
      case lit: AST.Exp.LitR => val r = transLitR(lit); return r
      case lit: AST.Exp.LitString => val r = transLitString(lit); return r
    }
  }

  def transpileSubZLit(posOpt: Option[Position], t: AST.Typed, value: String): ST = {
    val tname = typeName(t)
    val info: TypeInfo.SubZ = ts.typeHierarchy.typeMap.get(tname).get.asInstanceOf[TypeInfo.SubZ]
    val n = Z(value).get
    checkBitWidth(posOpt, n, info.ast.bitWidth, !info.ast.isSigned)
    return st"${mangleName(tname)}_C($n)"
  }

  def transObjectMethodInvoke(
    targs: ISZ[AST.Typed],
    retType: AST.Typed,
    name: ST,
    invokeArgs: ISZ[AST.Exp],
    closureVars: ISZ[ClosureVar]
  ): ST = {
    var args = ISZ[ST]()
    for (p <- ops.ISZOps(invokeArgs).zip(targs)) {
      val (arg, t) = p
      val a = transpileExp(arg)
      if (isScalar(typeKind(t))) {
        args = args :+ a
      } else {
        args = args :+ st"(${transpileType(t)}) $a"
      }
    }
    for (cv <- closureVars) {
      if (isScalar(typeKind(cv.t))) {
        if (cv.context == context) {
          args = args :+ st"&${localId(cv.id)}"
        } else {
          args = args :+ localId(cv.id)
        }
      } else {
        val id = localId(cv.id)
        if (cv.context == context && !cv.isVal) {
          stmts = stmts :+ st"if (&_$id != $id) { Type_assign(&_$id, $id, sizeof(${typeDecl(cv.t)})); $id = &_$id; }"
        }
        args = args :+ st"(${transpileType(cv.t)}) $id"
      }
    }
    if (isScalar(typeKind(retType)) || retType == AST.Typed.unit) {
      return st"$name(sf${commaArgs(args)})"
    } else {
      val temp = freshTempName()
      val tpe = transpileType(retType)
      stmts = stmts :+ st"DeclNew$tpe($temp);"
      stmts = stmts :+ st"$name(($tpe) &$temp, sf${commaArgs(args)});"
      return st"(($tpe) &$temp)"
    }
  }

  def transpileExp(expression: AST.Exp): ST = {

    if (config.expPlugins.nonEmpty) {
      for (p <- config.expPlugins if p.canTranspile(this, expression)) {
        val r = p.transpile(this, expression)
        return r
      }
    }

    def transSubZLit(exp: AST.Exp.StringInterpolate): ST = {
      val r = transpileSubZLit(exp.posOpt, expType(exp), exp.lits(0).value)
      return r
    }

    def transIdent(exp: AST.Exp.Ident): ST = {
      exp.attr.resOpt.get match {
        case res: AST.ResolvedInfo.LocalVar =>
          val id = exp.id.value
          localRename.get(id) match {
            case Some(otherId) => return otherId
            case _ =>
              res.scope match {
                case AST.ResolvedInfo.LocalVar.Scope.Closure =>
                  val t = expType(exp)
                  return if (isScalar(typeKind(t))) st"(*${localId(exp.id.value)})" else localId(exp.id.value)
                case _ => return localId(exp.id.value)
              }
          }
        case res: AST.ResolvedInfo.Var =>
          if (res.owner == AST.Typed.sireumName && (res.id == string"T" || res.id == string"F")) {
            return if (res.id == string"T") trueLit else falseLit
          } else {
            if (res.isInObject) {
              return st"${mangleName(res.owner)}_${res.id}(sf)"
            } else {
              val t = currReceiverOpt.get
              return st"${transpileType(t)}_${fieldId(res.id)}_(this)"
            }
          }
        case res: AST.ResolvedInfo.Method =>
          val t = res.tpeOpt.get.ret
          if (res.isInObject) {
            val r = transObjectMethodInvoke(res.tpeOpt.get.args, t, methodNameRes(None(), res), ISZ(), ISZ())
            return r
          } else {
            nestedMethods.get(res.owner :+ res.id) match {
              case Some((noTypeParam, m, cls)) =>
                currReceiverOpt match {
                  case Some(_) =>
                    val r = transInstanceMethodInvoke(
                      t,
                      methodName(currReceiverOpt, F, res.owner, res.id, noTypeParam, m.sig.funType),
                      currReceiverOpt.get,
                      st"this",
                      res.id,
                      ISZ(),
                      ISZ(),
                      cls
                    )
                    return r
                  case _ =>
                    val r = transObjectMethodInvoke(
                      res.tpeOpt.get.args,
                      t,
                      methodName(None(), F, res.owner, res.id, noTypeParam, m.sig.funType),
                      ISZ(),
                      cls
                    )
                    return r
                }
              case _ =>
                val r = transInstanceMethodInvoke(
                  t,
                  methodNameRes(Some(currReceiverOpt.get), res),
                  currReceiverOpt.get,
                  st"this",
                  res.id,
                  ISZ(),
                  ISZ(),
                  ISZ()
                )
                return r
            }
          }
        case res: AST.ResolvedInfo.EnumElement => return st"${mangleName(res.owner)}_${enumId(res.name)}"
        case res => halt(s"Infeasible: $res")
      }
    }

    def transBinary(exp: AST.Exp.Binary): ST = {
      exp.attr.resOpt.get match {
        case res: AST.ResolvedInfo.BuiltIn =>
          res.kind match {
            case AST.ResolvedInfo.BuiltIn.Kind.BinaryImply =>
              val left = transpileExp(exp.left)
              val right = transpileExp(exp.right)
              return st"(!($left) || $right)"
            case AST.ResolvedInfo.BuiltIn.Kind.BinaryCondAnd =>
              val left = transpileExp(exp.left)
              val right = transpileExp(exp.right)
              return st"($left && $right)"
            case AST.ResolvedInfo.BuiltIn.Kind.BinaryCondOr =>
              val left = transpileExp(exp.left)
              val right = transpileExp(exp.right)
              return st"($left || $right)"
            case AST.ResolvedInfo.BuiltIn.Kind.BinaryMapsTo =>
              val left = transpileExp(exp.left)
              val right = transpileExp(exp.right)
              val t = expType(exp)
              val tpe = transpileType(t)
              val temp = freshTempName()
              stmts = stmts :+ st"DeclNew$tpe($temp);"
              stmts = stmts :+ st"${tpe}_apply(sf, &$temp, $left, $right);"
              return st"(&$temp)"
            case _ =>
              val op: String = res.kind match {
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryAdd => "__add"
                case AST.ResolvedInfo.BuiltIn.Kind.BinarySub => "__sub"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryMul => "__mul"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryDiv => "__div"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryRem => "__rem"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryEq => "__eq"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryNe => "__ne"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryLt => "__lt"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryLe => "__le"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryGt => "__gt"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryGe => "__ge"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryShl => "__shl"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryShr => "__shr"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryUshr => "__ushr"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryAnd => "__and"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryOr => "__or"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryXor => "__xor"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryAppend => "__append"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryPrepend => "__prepend"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryAppendAll => "__appendall"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryRemoveAll => "__removeall"
                case AST.ResolvedInfo.BuiltIn.Kind.BinaryMapsTo =>
                  val left = transpileExp(exp.left)
                  val right = transpileExp(exp.right)
                  val t = expType(exp).asInstanceOf[AST.Typed.Tuple]
                  val tpe = transpileType(t)
                  val temp = freshTempName()
                  stmts = stmts :+ st"DeclNew$tpe($temp);"
                  stmts = stmts :+ st"${tpe}_apply(sf, &$temp, (${transpileType(t.args(0))}) $left, (${transpileType(t.args(1))}) $right);"
                  return st"(&$temp)"
                case _ => halt(s"Infeasible: $res.kind")
              }
              val left = transpileExp(exp.left)
              val right = transpileExp(exp.right)
              val t = expType(exp.left)
              return st"${transpileType(t)}$op($left, $right)"
          }
        case res: AST.ResolvedInfo.Method =>
          val receiver = transpileExp(exp.left)
          val receiverType = expType(exp.left).asInstanceOf[AST.Typed.Name]
          val r = transInstanceMethodInvoke(
            expType(exp),
            methodNameRes(Some(receiverType), res),
            receiverType,
            receiver,
            res.id,
            res.tpeOpt.get.args,
            ISZ(exp.right),
            ISZ()
          )
          return r
        case _ => halt("Infeasible")
      }
    }

    def transUnary(exp: AST.Exp.Unary): ST = {
      exp.attr.resOpt.get match {
        case res: AST.ResolvedInfo.BuiltIn =>
          if (res.kind == AST.ResolvedInfo.BuiltIn.Kind.UnaryNot) {
            val e = transpileExp(exp.exp)
            return st"(!$e)"
          } else {
            val tname = typeName(expType(exp))
            val op: String = res.kind match {
              case AST.ResolvedInfo.BuiltIn.Kind.UnaryComplement => "__complement"
              case AST.ResolvedInfo.BuiltIn.Kind.UnaryPlus => "__plus"
              case AST.ResolvedInfo.BuiltIn.Kind.UnaryMinus => "__minus"
              case _ => halt("Infeasible")
            }
            val e = transpileExp(exp.exp)
            return st"${mangleName(tname)}$op($e)"
          }
        case res: AST.ResolvedInfo.Method =>
          val receiver = transpileExp(exp.exp)
          val receiverType = expType(exp.exp).asInstanceOf[AST.Typed.Name]
          val t = expType(exp)
          val r =
            transInstanceMethodInvoke(t, methodNameRes(Some(receiverType), res), receiverType, receiver, res.id, ISZ(), ISZ(), ISZ())
          return r
        case _ => halt("Infeasible")
      }
    }

    def transTuple(tuple: AST.Exp.Tuple): ST = {
      val tpe = transpileType(tuple.typedOpt.get)
      val temp = freshTempName()
      var args = ISZ[ST]()
      for (arg <- tuple.args) {
        val a = transpileExp(arg)
        args = args :+ a
      }
      stmts = stmts :+ st"DeclNew$tpe($temp);"
      stmts = stmts :+ st"${tpe}_apply(sf, &$temp, ${(args, ", ")});"
      return st"(&$temp)"
    }

    def transSelectVar(receiver: AST.Exp, res: AST.ResolvedInfo.Var): ST = {
      val e = transpileExp(receiver)
      return st"${transpileType(expType(receiver))}_${fieldId(res.id)}_($e)"
    }

    def transSelect(select: AST.Exp.Select): ST = {
      select.attr.resOpt.get match {
        case res: AST.ResolvedInfo.Tuple =>
          val receiver = select.receiverOpt.get
          val o = transpileExp(receiver)
          val index = res.index
          val t = expType(receiver).asInstanceOf[AST.Typed.Tuple]
          val tpe = transpileType(t)
          return st"${tpe}_$index($o)"
        case res: AST.ResolvedInfo.EnumElement => return elementName(res.owner, res.name)
        case res: AST.ResolvedInfo.BuiltIn =>
          res.kind match {
            case AST.ResolvedInfo.BuiltIn.Kind.EnumElements =>
              val owner = expType(select.receiverOpt.get).asInstanceOf[AST.Typed.Enum].name
              val iszType = transpileType(expType(select))
              val temp = freshTempName()
              stmts = stmts :+ st"DeclNew$iszType($temp);"
              stmts = stmts :+ st"${mangleName(owner)}_elements(&$temp);"
              return st"(&$temp)"
            case AST.ResolvedInfo.BuiltIn.Kind.EnumNumOfElements =>
              val owner = expType(select.receiverOpt.get).asInstanceOf[AST.Typed.Enum].name
              val temp = freshTempName()
              stmts = stmts :+ st"Z $temp = ${mangleName(owner)}_numOfElements();"
              return temp
            case AST.ResolvedInfo.BuiltIn.Kind.AsInstanceOf =>
              val e = transpileExp(select.receiverOpt.get)
              val t = select.targs(0).typedOpt.get
              val tpe = transpileType(t)
              return st"${tpe}__as(sf, $e)"
            case AST.ResolvedInfo.BuiltIn.Kind.IsInstanceOf =>
              val e = transpileExp(select.receiverOpt.get)
              val t = select.targs(0).typedOpt.get
              val tpe = transpileType(t)
              return st"${tpe}__is(sf, $e)"
            case AST.ResolvedInfo.BuiltIn.Kind.String =>
              val receiver = select.receiverOpt.get
              val e = transpileExp(receiver)
              val t = expType(receiver)
              val temp = freshTempName()
              stmts = stmts :+ st"DeclNewString($temp);"
              stmts = stmts :+ st"${transpileType(t)}_string($temp, sf, $e);"
              return st"((String) &$temp)"
            case _ => halt("Infeasible")
          }
        case res: AST.ResolvedInfo.Method =>
          res.mode match {
            case AST.MethodMode.Method =>
              val t = expType(select)
              if (res.isInObject) {
                val r =
                  transObjectMethodInvoke(res.tpeOpt.get.args, t, methodNameRes(None(), res), ISZ(), ISZ())
                return r
              } else {
                val receiver = select.receiverOpt.get
                val receiverType = expType(receiver).asInstanceOf[AST.Typed.Name]
                val rcv = transpileExp(receiver)
                val r = transInstanceMethodInvoke(
                  t,
                  methodNameRes(Some(receiverType), res),
                  receiverType,
                  rcv,
                  res.id,
                  ISZ(),
                  ISZ(),
                  ISZ()
                )
                return r
              }
            case _ => halt(s"Infeasible: $res")
          }
        case res: AST.ResolvedInfo.Var =>
          if (res.isInObject) {
            return st"${mangleName(res.owner)}_${res.id}(sf)"
          } else {
            val receiver = select.receiverOpt.get
            return transSelectVar(receiver, res)
          }
        case _ => halt(s"Infeasible")
      }
    }

    def transInstanceMethodInvoke(
      retType: AST.Typed,
      name: ST,
      receiverType: AST.Typed.Name,
      receiver: ST,
      id: String,
      argTypes: ISZ[AST.Typed],
      invokeArgs: ISZ[AST.Exp],
      closureVars: ISZ[ClosureVar]
    ): ST = {
      val isVar: B = ts.typeHierarchy.typeMap.get(receiverType.ids).get match {
        case info: TypeInfo.Adt => info.vars.contains(id)
        case _ => F
      }
      if (isVar) {
        return st"${transpileType(receiverType)}_${fieldId(id)}_($receiver)"
      }
      var args = ISZ[ST]()
      for (p <- ops.ISZOps(invokeArgs).zip(argTypes)) {
        val (arg, t) = p
        val a = transpileExp(arg)
        if (isScalar(typeKind(t))) {
          args = args :+ a
        } else {
          args = args :+ st"(${transpileType(t)}) $a"
        }
      }
      for (cv <- closureVars) {
        if (isScalar(typeKind(cv.t))) {
          if (cv.context == context) {
            args = args :+ st"&${localId(cv.id)}"
          } else {
            args = args :+ localId(cv.id)
          }
        } else {
          val lid = localId(cv.id)
          if (cv.context == context && !cv.isVal) {
            stmts = stmts :+ st"if (&_$lid != $lid) { Type_assign(&_$lid, $lid, sizeof(${typeDecl(cv.t)})); $lid = &_$lid; }"
          }
          args = args :+ st"(${transpileType(cv.t)}) $lid"
        }
      }
      if (isScalar(typeKind(retType)) || retType == AST.Typed.unit) {
        return st"$name(sf, $receiver${commaArgs(args)})"
      } else {
        val temp = freshTempName()
        val tpe = transpileType(retType)
        stmts = stmts :+ st"DeclNew$tpe($temp);"
        stmts = stmts :+ st"$name(($tpe) &$temp, sf, $receiver${commaArgs(args)});"
        return st"(($tpe) &$temp)"
      }
    }

    def transExt(res: AST.ResolvedInfo.Method, retType: AST.Typed, invokeArgs: ISZ[AST.Exp]): ST = {
      val tpe = transpileType(retType)
      var args = ISZ[ST]()
      for (p <- ops.ISZOps(invokeArgs).zip(res.tpeOpt.get.args)) {
        val (arg, t) = p
        val a = transpileExp(arg)
        if (isScalar(typeKind(t))) {
          args = args :+ a
        } else {
          args = args :+ st"(${transpileType(t)}) $a"
        }
      }
      val name: ST = if (sNameSet.contains(res.owner)) st"${tpe}_${res.id}" else methodNameRes(None(), res)
      if (isScalar(typeKind(retType)) || retType == AST.Typed.unit) {
        return st"$name(sf${commaArgs(args)})"
      } else {
        val temp = freshTempName()
        stmts = stmts :+ st"DeclNew$tpe($temp);"
        stmts = stmts :+ st"$name(($tpe) &$temp, sf${commaArgs(args)});"
        return st"(($tpe) &$temp)"
      }
    }

    def transConstructor(method: AST.ResolvedInfo.Method, retType: AST.Typed, invokeArgs: ISZ[AST.Exp]): ST = {
      val tpe = transpileType(retType)
      val temp = freshTempName()
      var args = ISZ[ST]()
      for (p <- ops.ISZOps(invokeArgs).zip(method.tpeOpt.get.args)) {
        val (arg, t) = p
        val a = transpileExp(arg)
        if (isScalar(typeKind(t))) {
          args = args :+ a
        } else {
          args = args :+ st"(${transpileType(t)}) $a"
        }
      }
      stmts = stmts :+ st"DeclNew$tpe($temp);"
      stmts = stmts :+ st"${tpe}_apply(sf, &$temp${commaArgs(args)});"
      return st"(&$temp)"
    }

    def transInvoke(invoke: AST.Exp.Invoke): ST = {

      def transSApply(): ST = {
        val t = expType(invoke).asInstanceOf[AST.Typed.Name]
        val tpe = transpileType(t)
        val temp = freshTempName()
        val size = invoke.args.size
        val sizeType = arraySizeType(minIndexMaxElementSize(t)._2)
        stmts = stmts :+ st"""STATIC_ASSERT($size <= Max$tpe, "Insufficient maximum for $t elements.");"""
        stmts = stmts :+ st"DeclNew$tpe($temp);"
        stmts = stmts :+ st"$temp.size = ($sizeType) $size;"
        val targ = t.args(1)
        val targKind = typeKind(targ)
        if (isScalar(targKind)) {
          var i = 0
          for (arg <- invoke.args) {
            val a = transpileExp(arg)
            stmts = stmts :+ st"$temp.value[$i] = $a;"
            i = i + 1
          }
        } else {
          var i = 0
          for (arg <- invoke.args) {
            val a = transpileExp(arg)
            stmts = stmts :+ st"Type_assign(&$temp.value[$i], $a, sizeof(${typePrefix(targKind)}${transpileType(targ)}));"
            i = i + 1
          }
        }
        return st"(&$temp)"
      }

      def transReceiver(): ST = {
        invoke.receiverOpt match {
          case Some(receiver) =>
            invoke.ident.attr.resOpt.get match {
              case res: AST.ResolvedInfo.Var => val r = transSelectVar(receiver, res); return r
              case _ => val r = transpileExp(receiver); return r
            }
          case _ => val r = transpileExp(invoke.ident); return r
        }
      }

      def transSSelect(name: QName): ST = {
        val t = invoke.ident.typedOpt.get.asInstanceOf[AST.Typed.Name]
        val receiver = transReceiver()
        val arg = invoke.args(0)
        val tpe = transpileType(t)
        val e = transpileExp(arg)
        return st"${tpe}_at($receiver, $e)"
      }

      def transSStore(name: QName): ST = {
        val t = expType(invoke).asInstanceOf[AST.Typed.Name]
        val tpe = transpileType(t)
        val temp = freshTempName()
        val receiver = transReceiver()
        stmts = stmts :+ st"DeclNew$tpe($temp);"
        stmts = stmts :+ st"Type_assign(&$temp, $receiver, sizeof(struct $tpe));"
        val indexType = t.args(0)
        val elementType = t.args(1)
        val argType = AST.Typed.Tuple(ISZ(indexType, elementType))
        val argTpe = transpileType(argType)
        val argTypeKind = typeKind(elementType)
        if (isScalar(argTypeKind)) {
          for (arg <- invoke.args) {
            val e = transpileExp(arg)
            stmts = stmts :+ st"$temp.value[${argTpe}_1($e)] = ${argTpe}_2($e);"
          }
        } else {
          val elementTpe = transpileType(elementType)
          for (arg <- invoke.args) {
            val e = transpileExp(arg)
            stmts = stmts :+ st"Type_assign(&$temp.value[${argTpe}_1($e)], ${argTpe}_2($e), sizeof(${typePrefix(argTypeKind)}$elementTpe));"
          }
        }
        return st"(&$temp)"
      }

      invoke.attr.resOpt.get match {
        case res: AST.ResolvedInfo.Method =>
          res.mode match {
            case AST.MethodMode.Method =>
              if (res.isInObject) {
                val r =
                  transObjectMethodInvoke(
                    res.tpeOpt.get.args,
                    expType(invoke),
                    methodNameRes(None(), res),
                    invoke.args,
                    ISZ()
                  )
                return r
              } else {
                val rtRcvOpt: Option[(AST.Typed.Name, ST)] = invoke.receiverOpt match {
                  case Some(rcv) =>
                    val r = transpileExp(rcv)
                    Some((expType(rcv).asInstanceOf[AST.Typed.Name], r))
                  case _ =>
                    currReceiverOpt match {
                      case Some(rcv) => Some((rcv, st"this"))
                      case _ => None()
                    }
                }
                nestedMethods.get(res.owner :+ res.id) match {
                  case Some((noTypeParam, m, cls)) =>
                    rtRcvOpt match {
                      case Some((rcv, receiver)) =>
                        val r = transInstanceMethodInvoke(
                          expType(invoke),
                          methodName(Some(rcv), F, res.owner, res.id, noTypeParam, m.sig.funType),
                          rcv,
                          receiver,
                          res.id,
                          res.tpeOpt.get.args,
                          invoke.args,
                          cls
                        )
                        return r
                      case _ =>
                        val r =
                          transObjectMethodInvoke(
                            res.tpeOpt.get.args,
                            expType(invoke),
                            methodName(None(), F, res.owner, res.id, noTypeParam, m.sig.funType),
                            invoke.args,
                            cls
                          )
                        return r
                    }
                  case _ =>
                    rtRcvOpt match {
                      case Some((rcv, receiver)) =>
                        val r = transInstanceMethodInvoke(
                          expType(invoke),
                          methodNameRes(Some(rcv), res),
                          rcv,
                          receiver,
                          res.id,
                          res.tpeOpt.get.args,
                          invoke.args,
                          ISZ()
                        )
                        return r
                      case _ =>
                        val r = transObjectMethodInvoke(
                          res.tpeOpt.get.args,
                          expType(invoke),
                          methodNameRes(None(), res),
                          invoke.args,
                          ISZ(),
                        )
                        return r
                    }
                }
              }
            case AST.MethodMode.Spec => halt(s"TODO: $res") // TODO
            case AST.MethodMode.Ext => val r = transExt(res, expType(invoke), invoke.args); return r
            case AST.MethodMode.Constructor =>
              def basicConstructor(name: QName): ST = {
                val e = transpileExp(invoke.args(0))
                val tpe = transpileType(expType(invoke))
                val temp = freshTempName()
                stmts = stmts :+ st"DeclNew$tpe($temp);"
                stmts = stmts :+ st"${mangleName(name)}_apply(&$temp, $e);"
                return st"(&$temp)"
              }
              res.owner :+ res.id match {
                case AST.Typed.isName => val r = transSApply(); return r
                case AST.Typed.msName => val r = transSApply(); return r
                case AST.Typed.bName =>
                  if (!bConstructor) {
                    genBConstructor()
                  }
                  bConstructor = T
                  val r = basicConstructor(AST.Typed.bName)
                  return r
                case AST.Typed.zName =>
                  if (!zConstructor) {
                    genZConstructor()
                  }
                  zConstructor = T
                  val r = basicConstructor(AST.Typed.zName)
                  return r
                case AST.Typed.f32Name =>
                  if (!f32Constructor) {
                    val optTpe = transpileType(AST.Typed.Name(AST.Typed.optionName, ISZ(AST.Typed.f32)))
                    val someTpe = transpileType(AST.Typed.Name(AST.Typed.someName, ISZ(AST.Typed.f32)))
                    val noneTpe = transpileType(AST.Typed.Name(AST.Typed.noneName, ISZ(AST.Typed.f32)))
                    genFloatConstructor(AST.Typed.f32Name, optTpe, someTpe, noneTpe, "float", "strtof")
                  }
                  f32Constructor = T
                  val r = basicConstructor(AST.Typed.f32Name)
                  return r
                case AST.Typed.f64Name =>
                  if (!f64Constructor) {
                    val optTpe = transpileType(AST.Typed.Name(AST.Typed.optionName, ISZ(AST.Typed.f64)))
                    val someTpe = transpileType(AST.Typed.Name(AST.Typed.someName, ISZ(AST.Typed.f64)))
                    val noneTpe = transpileType(AST.Typed.Name(AST.Typed.noneName, ISZ(AST.Typed.f64)))
                    genFloatConstructor(AST.Typed.f64Name, optTpe, someTpe, noneTpe, "double", "strtod")
                  }
                  f64Constructor = T
                  val r = basicConstructor(AST.Typed.f64Name)
                  return r
                case AST.Typed.rName =>
                  if (!rConstructor) {
                    val optTpe = transpileType(AST.Typed.Name(AST.Typed.optionName, ISZ(AST.Typed.r)))
                    val someTpe = transpileType(AST.Typed.Name(AST.Typed.someName, ISZ(AST.Typed.r)))
                    val noneTpe = transpileType(AST.Typed.Name(AST.Typed.noneName, ISZ(AST.Typed.r)))
                    genFloatConstructor(AST.Typed.rName, optTpe, someTpe, noneTpe, "long double", "strtold")
                  }
                  rConstructor = T
                  val r = basicConstructor(AST.Typed.rName)
                  return r
                case AST.Typed.cName =>
                  val optTpe = transpileType(expType(invoke))
                  val someT = AST.Typed.Name(AST.Typed.someName, ISZ(AST.Typed.c))
                  val someTpe = transpileType(someT)
                  val noneT = AST.Typed.Name(AST.Typed.noneName, ISZ(AST.Typed.c))
                  val noneTpe = transpileType(noneT)
                  val e = transpileExp(invoke.args(0))
                  val temp = freshTempName()
                  stmts = stmts :+ st"DeclNew$optTpe($temp);"
                  stmts = stmts :+
                    st"""if ($e->size == 0) {
                    |  $temp.type = T$noneTpe;
                    |} else {
                    |  $temp.type = T$someTpe;
                    |  $temp.$someTpe.value = (C) $e->value[0];
                    |}"""
                  return st"(&$temp)"
                case name =>
                  ts.typeHierarchy.typeMap.get(name) match {
                    case Some(_: TypeInfo.SubZ) => val r = basicConstructor(name); return r
                    case _ => val r = transConstructor(res, expType(invoke), invoke.args); return r
                  }
              }
            case AST.MethodMode.Copy => val r = transConstructor(res, expType(invoke), invoke.args); return r
            case AST.MethodMode.Extractor => halt(s"Infeasible: $res")
            case AST.MethodMode.ObjectConstructor => halt(s"Infeasible: $res")
            case AST.MethodMode.Select => val r = transSSelect(res.owner :+ res.id); return r
            case AST.MethodMode.Store => val r = transSStore(res.owner :+ res.id); return r
          }
        case res: AST.ResolvedInfo.BuiltIn =>
          def enumInvoke(): ST = {
            val r = transObjectMethodInvoke(
              invoke.args.map(e => e.typedOpt.get),
              expType(invoke),
              methodNameTyped(None(), invoke.ident.attr.typedOpt.get.asInstanceOf[AST.Typed.Method]),
              invoke.args,
              ISZ()
            )
            return r
          }
          res.kind match {
            case AST.ResolvedInfo.BuiltIn.Kind.EnumByName => val r = enumInvoke(); return r
            case AST.ResolvedInfo.BuiltIn.Kind.EnumByOrdinal => val r = enumInvoke(); return r
            case _ => halt(s"Infeasible: $res")
          }
        case _ => halt("Infeasible")
      }
    }

    def transInvokeNamed(exp: AST.Exp.InvokeNamed): ST = {
      val args: ISZ[AST.Exp] = ops.ISZOps(exp.args).sortWith((na1, na2) => na1.index < na2.index).map(na => na.arg)
      val r = transInvoke(AST.Exp.Invoke(exp.receiverOpt, exp.ident, exp.targs, args, exp.attr))
      return r
    }

    def transStringInterpolate(exp: AST.Exp.StringInterpolate): ST = {
      val temp = freshTempName()
      stmts = stmts :+ st"DeclNewString($temp);"
      var i = 0
      for (arg <- exp.args) {
        val lit = exp.lits(i)
        val s = transpileLitString(lit.posOpt, lit.value)
        stmts = stmts :+ st"""String_string((String) &$temp, sf, $s);"""
        val t = expType(arg)
        val tpe = transpileType(t)
        val e = transpileExp(arg)
        stmts = stmts :+ st"${tpe}_string((String) &$temp, sf, $e);"
        i = i + 1
      }
      val lit = exp.lits(i)
      val s = transpileLitString(lit.posOpt, lit.value)
      stmts = stmts :+ st"""String_string((String) &$temp, sf, $s);"""
      return st"((String) &$temp)"
    }

    def transIf(exp: AST.Exp.If): ST = {
      val cond = transpileExp(exp.cond)
      val t = expType(exp)
      val scalar = isScalar(typeKind(t))
      val tpe = transpileType(t)
      val tDecl = typeDecl(t)
      val temp = freshTempName()
      if (scalar) {
        stmts = stmts :+ st"$tpe $temp;"
      } else {
        stmts = stmts :+ st"DeclNew$tpe($temp);"
      }
      val oldStmts = stmts
      stmts = ISZ()
      val thenExp = transpileExp(exp.thenExp)
      if (scalar) {
        stmts = stmts :+ st"$temp = $thenExp;"
      } else {
        stmts = stmts :+ st"Type_assign(&$temp, $thenExp, sizeof($tDecl));"
      }
      val thenStmts = stmts
      stmts = ISZ()
      val elseExp = transpileExp(exp.elseExp)
      if (scalar) {
        stmts = stmts :+ st"$temp = $elseExp;"
      } else {
        stmts = stmts :+ st"Type_assign(&$temp, $elseExp, sizeof($tDecl));"
      }
      stmts = oldStmts :+
        st"""if ($cond) {
        |  ${(thenStmts, "\n")}
        |} else {
        |  ${(stmts, "\n")}
        |}"""
      return if (scalar) st"$temp" else if (t == AST.Typed.string) st"((String) &$temp)" else st"&$temp"
    }

    def transForYield(exp: AST.Exp.ForYield): ST = {
      val temp = freshTempName()
      val t = expType(exp).asInstanceOf[AST.Typed.Name]
      val et = t.args(1)
      val index = freshTempName()
      val tpe = transpileType(t)
      stmts = stmts :+ st"DeclNew$tpe($temp);"
      val indexType = arraySizeType(minIndexMaxElementSize(t)._2)
      stmts = stmts :+ st"$indexType $index = 0;"
      val oldStmts = stmts
      stmts = ISZ()
      stmts = stmts :+ st"""sfAssert($index < Max$tpe, "Insufficient maximum for $t elements.");"""
      val e = transpileExp(exp.exp)
      if (isScalar(typeKind(et))) {
        stmts = stmts :+ st"$temp.value[$index] = $e;"
      } else {
        stmts = stmts :+ st"Type_assign(&$temp.value[$index], $e, sizeof(${typeDecl(et)}));"
      }
      stmts = stmts :+ st"$index++;"
      val egs = exp.enumGens
      var i = egs.size - 1
      var body = transpileEnumGen(egs(i), stmts)
      i = i - 1
      while (i >= 0) {
        body = transpileEnumGen(egs(i), body)
        i = i - 1
      }
      stmts = oldStmts ++ body
      stmts = stmts :+ st"$temp.size = $index;"
      return st"&$temp"
    }

    expression match {
      case exp: AST.Lit => val r = transpileLit(exp); return r
      case exp: AST.Exp.StringInterpolate =>
        exp.prefix.native match {
          case "s" => val r = transStringInterpolate(exp); return r
          case "st" =>
            reporter.error(exp.posOpt, transKind, "String template is not supported.")
            return abort
          case _ => val r = transSubZLit(exp); return r
        }
      case exp: AST.Exp.Ident => val r = transIdent(exp); return r
      case exp: AST.Exp.Binary => val r = transBinary(exp); return r
      case exp: AST.Exp.Unary => val r = transUnary(exp); return r
      case exp: AST.Exp.Select => val r = transSelect(exp); return r
      case exp: AST.Exp.Tuple => val r = transTuple(exp); return r
      case exp: AST.Exp.Invoke => val r = transInvoke(exp); return r
      case exp: AST.Exp.InvokeNamed => val r = transInvokeNamed(exp); return r
      case exp: AST.Exp.If => val r = transIf(exp); return r
      case _: AST.Exp.This => return st"this"
      case exp: AST.Exp.ForYield => val r = transForYield(exp); return r
      case _: AST.Exp.Super => halt("TODO") // TODO
      case _: AST.Exp.Eta => halt("TODO") // TODO
      case _: AST.Exp.Fun => halt("TODO") // TODO
      case _: AST.Exp.Quant => halt("TODO") // TODO
    }
  }

  def transToString(s: ST, exp: AST.Exp): Unit = {
    val tmp = transpileExp(exp)
    val mangledName = transpileType(expType(exp))
    stmts = stmts :+ st"${mangledName}_string($s, sf, $tmp);"
  }

  def transPrintH(isOut: ST, exp: AST.Exp): Unit = {
    val tmp = transpileExp(exp)
    val mangledName = transpileType(expType(exp))
    stmts = stmts :+ st"${mangledName}_cprint($tmp, $isOut);"
  }

  def expType(exp: AST.Exp): AST.Typed = {
    exp.typedOpt.get match {
      case t: AST.Typed.Method if t.tpe.isByName => return t.tpe.ret
      case t => return t
    }
  }

  def transpileBlock(stmt: AST.Stmt.Block): Unit = {
    val oldStmts = stmts
    stmts = ISZ()
    for (stmt <- stmt.body.stmts) {
      transpileStmt(stmt)
    }
    stmts = oldStmts :+
      st"""{
      |  ${(stmts, "\n")}
      |}"""
  }

  def transpileIf(stmt: AST.Stmt.If, fOpt: Option[(ST, ST) => ST @pure]): Unit = {
    val cond = transpileExp(stmt.cond)
    val oldStmts = stmts
    stmts = ISZ()
    fOpt match {
      case Some(f) =>
        val tstmts = stmt.thenBody.stmts
        for (stmt <- ops.ISZOps(tstmts).dropRight(1)) {
          transpileStmt(stmt)
        }
        transpileAssignExp(tstmts(tstmts.size - 1).asAssignExp, f)
      case _ =>
        for (stmt <- stmt.thenBody.stmts) {
          transpileStmt(stmt)
        }
    }
    if (stmt.elseBody.stmts.isEmpty) {
      stmts = oldStmts :+
        st"""if ($cond) {
        |  ${(stmts, "\n")}
        |}"""
    } else {
      val tstmts = stmts
      stmts = ISZ()
      fOpt match {
        case Some(f) =>
          val fstmts = stmt.elseBody.stmts
          for (stmt <- ops.ISZOps(fstmts).dropRight(1)) {
            transpileStmt(stmt)
          }
          transpileAssignExp(fstmts(fstmts.size - 1).asAssignExp, f)
        case _ =>
          for (stmt <- stmt.elseBody.stmts) {
            transpileStmt(stmt)
          }
      }
      stmts = oldStmts :+
        st"""if ($cond) {
        |  ${(tstmts, "\n")}
        |} else {
        |  ${(stmts, "\n")}
        |}"""
    }
  }

  @pure def idPatternName(allowShadow: B, id: AST.Id): ST = {
    if (allowShadow) {
      val pos = id.attr.posOpt.get
      return st"${id.value}_${pos.beginLine}_${pos.beginColumn}"
    } else {
      return st"${id.value}"
    }
  }

  def declPatternVars(allowShadow: B, pat: AST.Pattern): Unit = {
    def declId(id: AST.Id, t: AST.Typed): Unit = {
      val name = idPatternName(allowShadow, id)
      val kind = typeKind(t)
      val tpe = transpileType(t)
      if (isImmutable(kind)) {
        stmts = stmts :+ st"$tpe $name;"
      } else {
        stmts = stmts :+ st"DeclNew$tpe(_$name);"
        stmts = stmts :+ st"$tpe $name = &_$name;"
      }
    }
    pat match {
      case _: AST.Pattern.Literal =>
      case _: AST.Pattern.LitInterpolate =>
      case _: AST.Pattern.Ref =>
      case _: AST.Pattern.SeqWildcard =>
      case _: AST.Pattern.Wildcard =>
      case pat: AST.Pattern.VarBinding => declId(pat.id, pat.attr.typedOpt.get)
      case pat: AST.Pattern.Structure =>
        pat.idOpt match {
          case Some(id) => declId(id, pat.attr.typedOpt.get)
          case _ =>
        }
        for (p <- pat.patterns) {
          declPatternVars(allowShadow, p)
        }
    }
  }

  def transpilePattern(immutableParent: B, allowShadow: B, handledVar: ST, exp: ST, pattern: AST.Pattern): Unit = {
    def transTuplePattern(pat: AST.Pattern.Structure): Unit = {
      val oldStmts = stmts
      stmts = ISZ()
      val t = pat.attr.typedOpt.get
      val kind = typeKind(t)
      val tpe = transpileType(t)
      pat.idOpt match {
        case Some(id) =>
          val name = idPatternName(allowShadow, id)
          if (name.string != id.value) {
            localRename = localRename + id.value ~> name
          }
          stmts = stmts :+ st"$name = ($tpe) $exp;"
        case _ =>
      }
      val immutable = isImmutable(kind)
      for (pi <- ops.ISZOps(pat.patterns).zip(pat.patterns.indices.map((n: Z) => n + 1))) {
        val (p, i) = pi
        transpilePattern(immutable, allowShadow, handledVar, st"${tpe}_$i($exp)", p)
      }
      stmts = oldStmts :+
        st"""if ($handledVar) {
        |  ${(stmts, "\n")}
        |}"""
    }
    def transSPattern(t: AST.Typed.Name, pat: AST.Pattern.Structure): Unit = {
      val tpe = transpileType(t)
      val iTpe = transpileType(t.args(0))
      val hasWildcard: B =
        if (pat.patterns.size > 0) pat.patterns(pat.patterns.size - 1).isInstanceOf[AST.Pattern.SeqWildcard] else F
      if (hasWildcard) {
        stmts = stmts :+ st"$handledVar = $handledVar && ${iTpe}__ge(${tpe}_size($exp), ${iTpe}_C(${pat.patterns.size - 1}));"
      } else {
        stmts = stmts :+ st"$handledVar = $handledVar && ${iTpe}__eq(${tpe}_size($exp), ${iTpe}_C(${pat.patterns.size}));"
      }
      val oldStmts = stmts
      stmts = ISZ()
      pat.idOpt match {
        case Some(id) =>
          val name = idPatternName(allowShadow, id)
          if (name.string != id.value) {
            localRename = localRename + id.value ~> name
          }
          stmts = stmts :+ st"$name = ($tpe) $exp;"
        case _ =>
      }
      val immutable = isImmutable(typeKind(t))
      for (pi <- ops.ISZOps(pat.patterns).zip(pat.patterns.indices.map((n: Z) => n + 1))) {
        val (p, i) = pi
        transpilePattern(immutable, allowShadow, handledVar, st"${tpe}_at($exp, ${iTpe}_C($i))", p)
      }
      stmts = oldStmts :+
        st"""if ($handledVar) {
        |  ${(stmts, "\n")}
        |}"""
    }
    def transNamePattern(t: AST.Typed.Name, pat: AST.Pattern.Structure): Unit = {
      val tpe = transpileType(t)
      stmts = stmts :+ st"$handledVar = $handledVar && ${tpe}__is(sf, $exp);"
      val oldStmts = stmts
      stmts = ISZ()
      pat.idOpt match {
        case Some(id) =>
          val name = idPatternName(allowShadow, id)
          if (name.string != id.value) {
            localRename = localRename + id.value ~> name
          }
          stmts = stmts :+ st"$name = ($tpe) $exp;"
        case _ =>
      }
      val immutable = isImmutable(typeKind(t))
      val e = st"${tpe}__as(sf, $exp)"
      val adtInfo = ts.typeHierarchy.typeMap.get(t.ids).get.asInstanceOf[TypeInfo.Adt]
      for (idPattern <- ops.ISZOps(adtInfo.extractorTypeMap.keys).zip(pat.patterns)) {
        val (id, p) = idPattern
        transpilePattern(immutable, allowShadow, handledVar, st"${tpe}_${id}_($e)", p)
      }
      stmts = oldStmts :+
        st"""if ($handledVar) {
        |  ${(stmts, "\n")}
        |}"""
    }
    pattern match {
      case pat: AST.Pattern.Literal =>
        val e = transpileLit(pat.lit)
        pat.lit match {
          case _: AST.Exp.LitB => stmts = stmts :+ st"$handledVar = $handledVar && B__eq($exp, $e);"
          case _: AST.Exp.LitC => stmts = stmts :+ st"$handledVar = $handledVar && C__eq($exp, $e);"
          case _: AST.Exp.LitZ => stmts = stmts :+ st"$handledVar = $handledVar && Z__eq($exp, $e);"
          case _: AST.Exp.LitF32 => stmts = stmts :+ st"$handledVar = $handledVar && F32__eq($exp, $e);"
          case _: AST.Exp.LitF64 => stmts = stmts :+ st"$handledVar = $handledVar && F64__eq($exp, $e);"
          case _: AST.Exp.LitR => stmts = stmts :+ st"$handledVar = $handledVar && R__eq($exp, $e);"
          case _: AST.Exp.LitString =>
            stmts = stmts :+ st"$handledVar = $handledVar && String__eq((String) $exp, (String) $e);"
        }
      case pat: AST.Pattern.LitInterpolate =>
        pat.prefix.native match {
          case "z" =>
            val e = transpileLitZ(pat.posOpt, Z(pat.value).get)
            stmts = stmts :+ st"$handledVar = $handledVar && Z__eq($exp, $e);"
          case "c" =>
            val s = conversions.String.toCis(pat.value)
            val e = transpileLitC(pat.posOpt, s(0))
            stmts = stmts :+ st"$handledVar = $handledVar && C__eq($exp, $e);"
          case "f32" =>
            val e = transpileLitF32(F32(pat.value).get)
            stmts = stmts :+ st"$handledVar = $handledVar && F32__eq($exp, $e);"
          case "f64" =>
            val e = transpileLitF64(F64(pat.value).get)
            stmts = stmts :+ st"$handledVar = $handledVar && F64__eq($exp, $e);"
          case "r" =>
            val e = transpileLitR(R(pat.value).get)
            stmts = stmts :+ st"$handledVar = $handledVar && R__eq($exp, $e);"
          case "string" =>
            val e = transpileLitString(pat.posOpt, pat.value)
            stmts = stmts :+ st"$handledVar = $handledVar && String__eq((String) $exp, (String) $e);"
          case _ =>
            val t = pat.attr.typedOpt.get
            val e = transpileSubZLit(pat.posOpt, t, pat.value)
            stmts = stmts :+ st"$handledVar = $handledVar && ${transpileType(t)}__eq($exp, $e);"
        }
      case pat: AST.Pattern.Ref =>
        val t = pat.attr.typedOpt.get
        pat.attr.resOpt.get match {
          case res: AST.ResolvedInfo.LocalVar =>
            stmts = stmts :+ st"$handledVar = $handledVar && ${transpileType(t)}__eq($exp, ${localId(res.id)});"
          case res: AST.ResolvedInfo.Var =>
            if (res.isInObject) {
              stmts = stmts :+ st"$handledVar = $handledVar && ${transpileType(t)}__eq($exp, ${mangleName(res.owner)}_${res.id}(sf));"
            } else {
              stmts = stmts :+ st"$handledVar = $handledVar && ${transpileType(t)}__eq($exp, ${mangleName(res.owner)}_${res.id}_(this));"
            }
          case res: AST.ResolvedInfo.EnumElement =>
            stmts = stmts :+ st"$handledVar = $handledVar && ${transpileType(t)}__eq($exp, ${mangleName(res.owner)}_${enumId(res.name)});"
          case res => halt(s"Infeasible: $res")
        }
      case _: AST.Pattern.SeqWildcard => // skip
      case pat: AST.Pattern.Wildcard =>
        pat.typeOpt match {
          case Some(tpe) =>
            val t = tpe.typedOpt.get
            stmts = stmts :+ st"$handledVar = $handledVar && ${transpileType(t)}__is(sf, $exp);"
          case _ => // skip
        }
      case pat: AST.Pattern.VarBinding =>
        val name = idPatternName(allowShadow, pat.id)
        if (name.string != pat.id.value) {
          localRename = localRename + pat.id.value ~> name
        }
        val t = pat.attr.typedOpt.get
        val kind = typeKind(t)
        pat.tipeOpt match {
          case Some(tipe) =>
            stmts = stmts :+ st"$handledVar = $handledVar && ${transpileType(tipe.typedOpt.get)}__is(sf, $exp);"
            if (isScalar(kind)) {
              stmts = stmts :+ st"if ($handledVar) { $name = $exp; }"
            } else {
              if (immutableParent) {
                stmts = stmts :+ st"if ($handledVar) { $name = (${transpileType(t)}) $exp; }"
              } else {
                stmts = stmts :+ st"if ($handledVar) { Type_assign($name, $exp, sizeof(${typeDecl(t)})); }"
              }
            }
          case _ =>
            if (isScalar(kind)) {
              stmts = stmts :+ st"$name = $exp;"
            } else {
              if (immutableParent) {
                stmts = stmts :+ st"$name = (${transpileType(t)}) $exp;"
              } else {
                stmts = stmts :+ st"Type_assign($name, $exp, sizeof(${typeDecl(t)}));"
              }
            }
        }
      case pat: AST.Pattern.Structure =>
        pat.nameOpt match {
          case Some(_) =>
            val tName = pat.attr.typedOpt.get.asInstanceOf[AST.Typed.Name]
            tName.ids match {
              case AST.Typed.isName =>
              case AST.Typed.msName =>
              case AST.Typed.iszName =>
              case AST.Typed.mszName =>
              case AST.Typed.zsName =>
              case _ => transNamePattern(tName, pat); return
            }
            transSPattern(tName, pat)
          case _ => transTuplePattern(pat)
        }
    }
  }

  def transpileMatch(stmt: AST.Stmt.Match, fOpt: Option[(ST, ST) => ST @pure]): Unit = {
    val immutable = isImmutable(typeKind(expType(stmt.exp)))
    def transCase(handled: ST, exp: ST, cas: AST.Case): Unit = {
      val oldLocalRename = localRename
      val oldStmts = stmts
      stmts = ISZ()
      declPatternVars(T, cas.pattern)
      transpilePattern(immutable, T, handled, exp, cas.pattern)
      val patStmts = stmts
      stmts = ISZ()
      val condOpt: Option[ST] = cas.condOpt match {
        case Some(c) => val r = transpileExp(c); Some(r)
        case _ => None()
      }
      val condStmts = stmts
      stmts = ISZ()
      fOpt match {
        case Some(f) =>
          val bStmts = cas.body.stmts
          for (bStmt <- ops.ISZOps(bStmts).dropRight(1)) {
            transpileStmt(bStmt)
          }
          transpileAssignExp(bStmts(bStmts.size - 1).asAssignExp, f)
        case _ =>
          for (bStmt <- cas.body.stmts) {
            transpileStmt(bStmt)
          }
      }
      condOpt match {
        case Some(cond) =>
          stmts = oldStmts :+
            st"""if (!$handled) {
            |  $handled = T;
            |  ${(patStmts, "\n")}
            |  if ($handled) {
            |    ${(condStmts, "\n")}
            |    if ($cond) {
            |      ${(stmts, "\n")}
            |    } else {
            |      $handled = F;
            |    }
            |  }
            |}"""
        case _ =>
          stmts = oldStmts :+
            st"""if (!$handled) {
            |  $handled = T;
            |  ${(patStmts, "\n")}
            |  if ($handled) {
            |    ${(stmts, "\n")}
            |  }
            |}"""
      }
      localRename = oldLocalRename
    }
    val e: ST = stmt.exp match {
      case e: AST.Exp.Select if isNativeRes(e.attr.resOpt.get) => val r = transpileExp(e.receiverOpt.get); r
      case _ => val r = transpileExp(stmt.exp); r
    }
    val temp = freshTempName()
    val handled: ST = stmt.exp.posOpt match {
      case Some(pos) => st"match_${pos.beginLine}"
      case _ => freshTempName()
    }
    val t = expType(stmt.exp)
    val tpe = transpileType(t)
    val kind = typeKind(t)
    val tmp: ST = if (isScalar(kind)) {
      stmts = stmts :+ st"$tpe $temp = $e;"
      temp
    } else {
      stmts = stmts :+ st"DeclNew$tpe($temp);"
      stmts = stmts :+ st"Type_assign(&$temp, $e, sizeof(${typeDecl(t)}));"
      st"&$temp"
    }
    stmts = stmts :+ st"B $handled = F;"
    for (cas <- stmt.cases) {
      transCase(handled, tmp, cas)
    }
    stmts = stmts :+ st"""sfAssert($handled, "Error when pattern matching.");"""
  }

  @pure def transpileLoc(posOpt: Option[Position]): ISZ[ST] = {
    var r = ISZ(empty)
    posOpt match {
      case Some(pos) => r = r :+ st"sfUpdateLoc(${pos.beginLine});"
      case _ => r = r :+ st"sfUpdateLoc(0);"
    }
    return r
  }

  def transpileEnumGen(eg: AST.EnumGen.For, body: ISZ[ST]): ISZ[ST] = {
    stmts = ISZ()
    val b: ISZ[ST] = eg.condOpt match {
      case Some(cond) =>
        val c = transpileExp(cond)
        ISZ(st"""if ($c) {
        |  ${(body, "\n")}
        |}""")
      case _ => body
    }
    stmts = ISZ()
    eg.range match {
      case range: AST.EnumGen.Range.Step =>
        val start = transpileExp(range.start)
        val end = transpileExp(range.end)
        val (by, byE): (ST, Either[Z, ST]) = range.byOpt match {
          case Some(byExp) =>
            byExp match {
              case byExp: AST.Exp.LitZ => val v = byExp.value; (st"$v", Either.Left(v))
              case _ => val v = transpileExp(byExp); (v, Either.Right(v))
            }
          case _ => (st"1", Either.Left(1))
        }
        val id: ST = eg.idOpt match {
          case Some(x) => st"${x.value}"
          case _ => freshTempName()
        }
        val tpe = transpileType(expType(range.start))
        stmts = stmts :+ st"$tpe $id = $start;"
        val endTemp = freshTempName()
        stmts = stmts :+ st"$tpe $endTemp = $end;"
        val byTemp = freshTempName()
        stmts = stmts :+ st"Z $byTemp = $by;"
        val pos =
          st"""while ($id ${if (range.isInclusive) "<=" else "<"} $endTemp) {
          |  ${(b ++ transpileLoc(range.attr.posOpt), "\n")}
          |  $id = ($tpe) ($id + $byTemp);
          |}"""
        val neg =
          st"""while ($id ${if (range.isInclusive) ">=" else ">"} $endTemp) {
          |  ${(b ++ transpileLoc(range.attr.posOpt), "\n")}
          |  $id = ($tpe) ($id + $byTemp);
          |}"""
        byE match {
          case Either.Left(n) =>
            if (n > 0) {
              stmts = stmts :+ pos
            } else {
              stmts = stmts :+ neg
            }
          case _ =>
            stmts = stmts :+
              st"""if ($byTemp > 0) {
              |  $pos
              |} else {
              |  $neg
              |}"""
        }
        return stmts
      case range: AST.EnumGen.Range.Expr =>
        val e = transpileExp(range.exp)
        val t = expType(range.exp).asInstanceOf[AST.Typed.Name]
        val it = t.args(0)
        val et = t.args(1)
        val tpe = transpileType(t)
        val temp = freshTempName()
        stmts = stmts :+ st"$tpe $temp = $e;"
        val size = freshTempName()
        val index = freshTempName()
        val (minIndex, maxElements) = minIndexMaxElementSize(t)
        val indexType = arraySizeType(maxElements)
        stmts = stmts :+ st"$indexType $size = ($e)->size;"
        //(range.isIndices, range.isReverse) match {
        //  case (F, F) =>
            eg.idOpt match {
              case Some(id) =>
                val eTpe = transpileType(et)
                return if (isScalar(typeKind(et)))
                  stmts :+ st"""for ($indexType $index = 0; $index < $size; $index++) {
                  |  $eTpe ${id.value} = $temp->value[$index];
                  |  ${(b, "\n")}
                  |}"""
                else
                  stmts :+ st"""for ($indexType $index = 0; $index < $size; $index++) {
                  |  $eTpe ${id.value} = ($eTpe) &($temp->value[$index]);
                  |  ${(b, "\n")}                
                  |}"""
              case _ =>
                return stmts :+ st"""for ($indexType $index = 0; $index < $size; $index++) {
                |  ${(b, "\n")}
                |}"""
            }
        /*  case (F, T) =>
            eg.idOpt match {
              case Some(id) =>
                val eTpe = transpileType(et)
                return if (isScalar(typeKind(et)))
                  stmts :+ st"""for ($indexType $index = $size - 1; $index >= 0; $index--) {
                  |  $eTpe ${id.value} = $temp->value[$index];
                  |  ${(b, "\n")}
                  |}"""
                else
                  stmts :+ st"""for ($indexType $index = $size - 1; $index >= 0; $index--) {
                  |  $eTpe ${id.value} = ($eTpe) &($temp->value[$index]);
                  |  ${(b, "\n")}
                  |}"""
              case _ =>
                return stmts :+ st"""for ($indexType $index = $size - 1; $index >= 0; $index--) {
                |  ${(b, "\n")}
                |}"""
            }
          case (T, F) =>
            eg.idOpt match {
              case Some(id) =>
                val iTpe = transpileType(it)
                if (minIndex == z"0") {
                  return stmts :+ st"""for ($indexType $index = 0; $index < $size; $index++) {
                  |  $iTpe ${id.value} = ($iTpe) $index;
                  |  ${(b, "\n")}
                  |}"""

                } else {
                  return stmts :+ st"""for ($indexType $index = 0; $index < $size; $index++) {
                  |  $iTpe ${id.value} = ($iTpe) ((intmax_t) $index + $minIndex);
                  |  ${(b, "\n")}
                  |}"""
                }
              case _ =>
                return stmts :+ st"""for ($indexType $index = 0; $index < $size; $index++) {
                |  ${(b, "\n")}
                |}"""
            }
          case (T, T) =>
            eg.idOpt match {
              case Some(id) =>
                val iTpe = transpileType(it)
                if (minIndex == z"0") {
                  return stmts :+ st"""for ($indexType $index = $size - 1; $index >= 0; $index--) {
                  |  $iTpe ${id.value} = ($iTpe) $index;
                  |  ${(b, "\n")}
                  |}"""
                } else {
                  return stmts :+ st"""for ($indexType $index = $size - 1; $index >= 0; $index--) {
                  |  $iTpe ${id.value} = ($iTpe) ((intmax_t) $index + $minIndex);
                  |  ${(b, "\n")}
                  |}"""
                }
              case _ =>
                return stmts :+ st"""for ($indexType $index = $size - 1; $index >= 0; $index--) {
                |  ${(b, "\n")}
                |}"""
            }
          case _ => halt("Infeasible")
        }*/
    }
  }

  def transpileStmt(statement: AST.Stmt): Unit = {

    if (config.stmtPlugins.nonEmpty) {
      for (p <- config.stmtPlugins if p.canTranspile(this, statement)) {
        p.transpile(this, statement)
        return
      }
    }

    def transVar(stmt: AST.Stmt.Var): Unit = {
      stmts = stmts ++ transpileLoc(stmt.posOpt)
      val init = stmt.initOpt.get
      val t: AST.Typed = stmt.tipeOpt match {
        case Some(tipe) => tipe.typedOpt.get
        case _ => init.asInstanceOf[AST.Stmt.Expr].typedOpt.get
      }
      val local = localId(stmt.id.value)
      val tpe = transpileType(t)
      val kind = typeKind(t)
      val scalar = isScalar(kind)
      val immutable = isImmutable(kind)
      if (scalar) {
        init match {
          case _: AST.Stmt.Expr => transpileAssignExp(init, (rhs, _) => st"$tpe $local = $rhs;")
          case _ =>
            stmts = stmts :+ st"$tpe $local;"
            transpileAssignExp(init, (rhs, _) => st"$local = $rhs;")
        }
      } else {
        if (immutable && stmt.isVal && init.isInstanceOf[AST.Stmt.Expr]) {
          transpileAssignExp(init, (rhs, _) => st"$tpe $local = ($tpe) $rhs;")
        } else {
          stmts = stmts :+ st"DeclNew$tpe(_$local);"
          stmts = stmts :+ st"$tpe $local = ($tpe) &_$local;"
          transpileAssignExp(init, (rhs, rhsT) => st"Type_assign($local, $rhs, sizeof($rhsT));")
        }
      }
    }

    def transVarPattern(stmt: AST.Stmt.VarPattern): Unit = {
      stmts = stmts ++ transpileLoc(stmt.posOpt)
      val t: AST.Typed = stmt.tipeOpt match {
        case Some(tipe) => tipe.typedOpt.get
        case _ => stmt.init.asInstanceOf[AST.Stmt.Expr].typedOpt.get
      }
      val temp = freshTempName()
      val tpe = transpileType(t)
      stmts = stmts :+ st"$tpe $temp;"
      if (isScalar(typeKind(t))) {
        transpileAssignExp(stmt.init, (rhs, _) => st"$temp = $rhs;")
      } else {
        transpileAssignExp(stmt.init, (rhs, _) => st"$temp = ($tpe) $rhs;")
      }
      val handled = freshTempName()
      stmts = stmts :+ st"B $handled = T;"
      declPatternVars(F, stmt.pattern)
      val oldStmts = stmts
      val oldLocalRename = localRename
      stmts = ISZ()
      transpilePattern(isImmutable(typeKind(t)), F, handled, temp, stmt.pattern)
      stmts = oldStmts :+
        st"""{
        |  ${(stmts, "\n")}
        |}"""
      stmts = stmts :+ st"""sfAssert($handled, "Error during var pattern matching.");"""
      localRename = oldLocalRename
    }

    def transAssign(stmt: AST.Stmt.Assign): Unit = {
      stmts = stmts ++ transpileLoc(stmt.posOpt)
      stmt.lhs match {
        case lhs: AST.Exp.Ident =>
          lhs.attr.resOpt.get match {
            case res: AST.ResolvedInfo.LocalVar =>
              res.scope match {
                case AST.ResolvedInfo.LocalVar.Scope.Closure =>
                  val t = expType(stmt.lhs)
                  val kind = typeKind(t)
                  if (isScalar(kind)) {
                    transpileAssignExp(stmt.rhs, (rhs, _) => st"*${localId(lhs.id.value)} = $rhs;")
                  } else {
                    val id = localId(lhs.id.value)
                    transpileAssignExp(stmt.rhs, (rhs, _) => st"Type_assign($id, $rhs, sizeof(${typeDecl(t)}));")
                  }
                case scope =>
                  val t = expType(stmt.lhs)
                  val kind = typeKind(t)
                  if (isScalar(kind) || (isImmutable(kind) && scope == AST.ResolvedInfo.LocalVar.Scope.Current)) {
                    transpileAssignExp(stmt.rhs, (rhs, _) => st"${localId(lhs.id.value)} = $rhs;")
                  } else {
                    val id = localId(lhs.id.value)
                    stmts = stmts :+ st"${localId(lhs.id.value)} = &_$id;"
                    transpileAssignExp(stmt.rhs, (rhs, _) => st"Type_assign($id, $rhs, sizeof(${typeDecl(t)}));")
                  }
              }
            case res: AST.ResolvedInfo.Var =>
              if (res.isInObject) {
                val name = mangleName(res.owner :+ res.id)
                transpileAssignExp(stmt.rhs, (rhs, _) => st"${name}_a(sf, (${transpileType(expType(lhs))}) $rhs);")
              } else {
                val t = currReceiverOpt.get
                transpileAssignExp(
                  stmt.rhs,
                  (rhs, _) => st"${transpileType(t)}_${fieldId(res.id)}_a(this,(${transpileType(expType(lhs))}) $rhs);"
                )
              }
            case _ => halt("Infeasible")
          }
        case lhs: AST.Exp.Select =>
          val res = lhs.attr.resOpt.get.asInstanceOf[AST.ResolvedInfo.Var]
          if (res.isInObject) {
            val name = mangleName(res.owner :+ res.id)
            transpileAssignExp(stmt.rhs, (rhs, _) => st"${name}_a(sf, (${transpileType(expType(lhs))}) $rhs);")
          } else {
            val t = expType(lhs.receiverOpt.get)
            transpileAssignExp(
              stmt.rhs,
              (rhs, _) => st"${transpileType(t)}_${fieldId(res.id)}_a(this, (${transpileType(expType(lhs))}) $rhs);"
            )
          }
        case lhs: AST.Exp.Invoke =>
          val (receiverType, receiver): (AST.Typed.Name, ST) = lhs.receiverOpt match {
            case Some(rcv) => val r = transpileExp(rcv); (expType(rcv).asInstanceOf[AST.Typed.Name], r)
            case _ => val r = transpileExp(lhs.ident); (expType(lhs.ident).asInstanceOf[AST.Typed.Name], r)
          }
          val et = receiverType.args(1)
          val index = transpileExp(lhs.args(0))
          if (isScalar(typeKind(et))) {
            transpileAssignExp(stmt.rhs, (rhs, _) => st"${transpileType(receiverType)}_at($receiver, $index) = $rhs;")
          } else {
            transpileAssignExp(
              stmt.rhs,
              (rhs, _) =>
                st"Type_assign(${transpileType(receiverType)}_at($receiver, $index), $rhs, sizeof(${typeDecl(et)}));"
            )
          }
        case _ => halt("Infeasible")
      }

    }

    def transAssert(exp: AST.Exp.Invoke): Unit = {
      stmts = stmts ++ transpileLoc(statement.posOpt)
      val kind: AST.ResolvedInfo.BuiltIn.Kind.Type = exp.attr.resOpt.get match {
        case AST.ResolvedInfo.BuiltIn(k) => k
        case _ => halt("Infeasible")
      }
      val cond = transpileExp(exp.args(0))
      if (kind == AST.ResolvedInfo.BuiltIn.Kind.Assert) {
        stmts = stmts :+ st"""if (!($cond)) { sfAbort("Assertion failure"); }"""
      } else {
        assert(kind == AST.ResolvedInfo.BuiltIn.Kind.AssertMsg)
        val oldStmts = stmts
        stmts = ISZ()
        val s = transpileExp(exp.args(1))
        stmts = oldStmts :+
          st"""if (!($cond)) {
          |  ${(stmts, "\n")}
          |  sfAbort(($s)->value);
          |}"""
      }
    }

    def transAssume(exp: AST.Exp.Invoke): Unit = {
      stmts = stmts ++ transpileLoc(statement.posOpt)
      val kind: AST.ResolvedInfo.BuiltIn.Kind.Type = exp.attr.resOpt.get match {
        case AST.ResolvedInfo.BuiltIn(k) => k
        case _ => halt("Infeasible")
      }
      val cond = transpileExp(exp.args(0))
      if (kind == AST.ResolvedInfo.BuiltIn.Kind.Assume) {
        stmts = stmts :+ st"""if (!($cond)) { sfAbort("Assumption does not hold"); }"""
      } else {
        assert(kind == AST.ResolvedInfo.BuiltIn.Kind.AssumeMsg)
        val oldStmts = stmts
        stmts = ISZ()
        val s = transpileExp(exp.args(1))
        stmts = oldStmts :+
          st"""if (!($cond)) {
          |  ${(stmts, "\n")}
          |  sfAbort(($s)->value);
          |}"""
      }
    }

    def transCprint(exp: AST.Exp.Invoke): Unit = {
      stmts = stmts ++ transpileLoc(statement.posOpt)
      val t = transpileExp(exp.args(0))
      for (i <- z"1" until exp.args.size) {
        transPrintH(t, exp.args(i))
      }
    }

    def transCprintln(exp: AST.Exp.Invoke): Unit = {
      stmts = stmts ++ transpileLoc(statement.posOpt)
      val t = transpileExp(exp.args(0))
      val t2 = freshTempName()
      stmts = stmts :+ st"B $t2 = $t;"
      for (i <- z"1" until exp.args.size) {
        transPrintH(t2, exp.args(i))
      }
      stmts = stmts :+ st"cprintln($t2);"
      stmts = stmts :+ st"cflush($t2);"
    }

    def transEprint(exp: AST.Exp.Invoke): Unit = {
      stmts = stmts ++ transpileLoc(statement.posOpt)
      for (i <- z"0" until exp.args.size) {
        transPrintH(falseLit, exp.args(i))
      }
    }

    def transEprintln(exp: AST.Exp.Invoke): Unit = {
      stmts = stmts ++ transpileLoc(statement.posOpt)
      for (i <- z"0" until exp.args.size) {
        transPrintH(falseLit, exp.args(i))
      }
      stmts = stmts :+ st"cprintln($falseLit);"
      stmts = stmts :+ st"cflush($falseLit);"
    }

    def transPrint(exp: AST.Exp.Invoke): Unit = {
      stmts = stmts ++ transpileLoc(statement.posOpt)
      for (i <- z"0" until exp.args.size) {
        transPrintH(trueLit, exp.args(i))
      }
    }

    def transPrintln(exp: AST.Exp.Invoke): Unit = {
      stmts = stmts ++ transpileLoc(statement.posOpt)
      for (i <- z"0" until exp.args.size) {
        transPrintH(trueLit, exp.args(i))
      }
      stmts = stmts :+ st"cprintln($trueLit);"
      stmts = stmts :+ st"cflush($trueLit);"
    }

    def transHalt(exp: AST.Exp.Invoke): Unit = {
      stmts = stmts ++ transpileLoc(statement.posOpt)
      val tmp = declString()
      transToString(tmp, exp.args(0))
      stmts = stmts :+ st"sfAbort($tmp->value);"
      stmts = stmts :+ abort
    }

    def isBuiltInStmt(exp: AST.Exp.Invoke): B = {
      exp.attr.resOpt match {
        case Some(AST.ResolvedInfo.BuiltIn(kind)) =>
          kind match {
            case AST.ResolvedInfo.BuiltIn.Kind.Assert => return T
            case AST.ResolvedInfo.BuiltIn.Kind.AssertMsg => return T
            case AST.ResolvedInfo.BuiltIn.Kind.Assume => return T
            case AST.ResolvedInfo.BuiltIn.Kind.AssumeMsg => return T
            case AST.ResolvedInfo.BuiltIn.Kind.Cprint => return T
            case AST.ResolvedInfo.BuiltIn.Kind.Cprintln => return T
            case AST.ResolvedInfo.BuiltIn.Kind.Eprint => return T
            case AST.ResolvedInfo.BuiltIn.Kind.Eprintln => return T
            case AST.ResolvedInfo.BuiltIn.Kind.Print => return T
            case AST.ResolvedInfo.BuiltIn.Kind.Println => return T
            case AST.ResolvedInfo.BuiltIn.Kind.Halt => return T
            case _ => return F
          }
        case _ => return F
      }
    }

    def transpileWhile(stmt: AST.Stmt.While): Unit = {
      stmts = stmts ++ transpileLoc(stmt.posOpt)
      val cond = transpileExp(stmt.cond)
      val tmp: String = stmt.posOpt match {
        case Some(pos) => s"t_${pos.beginLine}_${pos.beginColumn}"
        case _ =>
          var h = stmt.hash
          if (h < 0) {
            h = h * h
          }
          s"t__$h"
      }
      stmts = stmts :+ st"B $tmp = $cond;"
      val oldStmts = stmts
      stmts = ISZ()
      for (stmt <- stmt.body.stmts) {
        transpileStmt(stmt)
      }
      stmts = stmts ++ transpileLoc(stmt.posOpt)
      val cond2 = transpileExp(stmt.cond)
      stmts = stmts :+ st"$tmp = $cond2;"
      stmts = oldStmts :+
        st"""while($tmp) {
        |  ${(stmts, "\n")}
        |}"""
    }

    def transpileDoWhile(stmt: AST.Stmt.DoWhile): Unit = {
      stmts = stmts ++ transpileLoc(stmt.posOpt)
      val oldStmts = stmts
      stmts = ISZ()
      for (stmt <- stmt.body.stmts) {
        transpileStmt(stmt)
      }
      val cond = transpileExp(stmt.cond)
      stmts = oldStmts :+
        st"""{
        |  ${(stmts, "\n")}
        |} while($cond);"""
    }

    def transpileFor(stmt: AST.Stmt.For): Unit = {
      stmts = stmts ++ transpileLoc(stmt.posOpt)
      val oldStmts = stmts
      stmts = ISZ()
      for (stmt <- stmt.body.stmts) {
        transpileStmt(stmt)
      }
      val egs = stmt.enumGens
      var body = transpileEnumGen(egs(egs.size - 1), stmts)
      for (i <- (egs.size - 2) to z"0" by -1) {
        body = transpileEnumGen(egs(i), body)
      }
      stmts = oldStmts :+
        st"""{
        |  ${(body, "\n")}
        |}"""
    }

    def transpileReturn(stmt: AST.Stmt.Return): Unit = {
      stmt.expOpt match {
        case Some(exp) =>
          val t = expType(exp)
          val e = transpileExp(exp)
          if (isScalar(typeKind(t))) {
            stmts = stmts :+ st"return $e;"
          } else {
            stmts = stmts :+ st"Type_assign(result, $e, sizeof(${typeDecl(t)}));"
          }
        case _ => stmts = stmts :+ st"return;"
      }
    }

    statement match {
      case stmt: AST.Stmt.Var => transVar(stmt)
      case stmt: AST.Stmt.Assign => transAssign(stmt)
      case stmt: AST.Stmt.Expr =>
        stmt.exp match {
          case exp: AST.Exp.Invoke =>
            if (isBuiltInStmt(exp)) {
              exp.attr.resOpt.get.asInstanceOf[AST.ResolvedInfo.BuiltIn].kind match {
                case AST.ResolvedInfo.BuiltIn.Kind.Assert => transAssert(exp)
                case AST.ResolvedInfo.BuiltIn.Kind.AssertMsg => transAssert(exp)
                case AST.ResolvedInfo.BuiltIn.Kind.Assume => transAssume(exp)
                case AST.ResolvedInfo.BuiltIn.Kind.AssumeMsg => transAssume(exp)
                case AST.ResolvedInfo.BuiltIn.Kind.Halt => transHalt(exp)
                case kind =>
                  stmts = stmts :+ empty
                  stmts = stmts :+ st"#ifndef SIREUM_NO_PRINT"
                  kind match {
                    case AST.ResolvedInfo.BuiltIn.Kind.Cprint => transCprint(exp)
                    case AST.ResolvedInfo.BuiltIn.Kind.Cprintln => transCprintln(exp)
                    case AST.ResolvedInfo.BuiltIn.Kind.Eprint => transEprint(exp)
                    case AST.ResolvedInfo.BuiltIn.Kind.Eprintln => transEprintln(exp)
                    case AST.ResolvedInfo.BuiltIn.Kind.Print => transPrint(exp)
                    case AST.ResolvedInfo.BuiltIn.Kind.Println => transPrintln(exp)
                    case _ => halt("Infeasible")
                  }
                  stmts = stmts :+ empty
                  stmts = stmts :+ st"#endif"
              }
            } else {
              stmts = stmts ++ transpileLoc(stmt.posOpt)
              val e = transpileExp(exp)
              val t = expType(exp)
              if (t == AST.Typed.unit) {
                stmts = stmts :+ st"$e;"
              } else {
                val temp = freshTempName()
                stmts = stmts :+ st"${transpileType(t)} $temp = $e;"
              }
            }
          case exp => halt(s"Infeasible: $exp")
        }
      case stmt: AST.Stmt.VarPattern => transVarPattern(stmt)
      case stmt: AST.Stmt.Block => transpileBlock(stmt)
      case stmt: AST.Stmt.If =>
        stmts = stmts ++ transpileLoc(stmt.posOpt)
        transpileIf(stmt, None())
      case stmt: AST.Stmt.While => transpileWhile(stmt)
      case stmt: AST.Stmt.DoWhile => transpileDoWhile(stmt)
      case stmt: AST.Stmt.Match =>
        stmts = stmts ++ transpileLoc(stmt.posOpt)
        transpileMatch(stmt, None())
      case stmt: AST.Stmt.For => transpileFor(stmt)
      case stmt: AST.Stmt.Return => transpileReturn(stmt)
      case _: AST.Stmt.Method => // skip
      case _: AST.Stmt.Import => // skip
      case _: AST.Stmt.Adt => // skip
      case _: AST.Stmt.Sig => // skip
      case _: AST.Stmt.Enum => // skip
      case _: AST.Stmt.Object => // skip
      case _: AST.Stmt.SpecMethod => // skip
      case _: AST.Stmt.ExtMethod => // skip
      case _: AST.Stmt.SpecVar => // skip
      case _: AST.Stmt.TypeAlias => // skip
      case _: AST.Stmt.LStmt => // skip
      case _: AST.Stmt.SubZ => // skip
    }
  }

  @memoize def getIntConst(e: AST.Exp): Option[Z] = {
    @pure def fromRes(res: AST.ResolvedInfo): Option[Z] = {
      res match {
        case res: AST.ResolvedInfo.Var if res.isInObject =>
          val info = ts.typeHierarchy.nameMap.get(res.owner :+ res.id).get.asInstanceOf[Info.Var]
          info.ast.initOpt.get match {
            case init: AST.Stmt.Expr if info.ast.isVal => return getIntConst(init.exp)
            case _ => return None()
          }
        case _ => return None()
      }
    }
    e match {
      case e: AST.Exp.LitZ => return Some(e.value)
      case e: AST.Exp.StringInterpolate =>
        e.typedOpt.get match {
          case t: AST.Typed.Name =>
            ts.typeHierarchy.typeMap.get(t.ids).get match {
              case _: TypeInfo.SubZ => return Z(e.lits(0).value)
              case _ => return None()
            }
          case _ => return None()
        }
      case e: AST.Exp.Ident => return fromRes(e.attr.resOpt.get)
      case e: AST.Exp.Select => return fromRes(e.attr.resOpt.get)
      case _ => return None()
    }
  }

  def genZConstructor(): Unit = {
    val optTpe = transpileType(AST.Typed.Name(AST.Typed.optionName, ISZ(AST.Typed.z)))
    val someTpe = transpileType(AST.Typed.Name(AST.Typed.someName, ISZ(AST.Typed.z)))
    val noneTpe = transpileType(AST.Typed.Name(AST.Typed.noneName, ISZ(AST.Typed.z)))
    val cTypeUp = st"INT${config.defaultBitWidth}"
    val (header, impl) = strToNum(
      AST.Typed.zName,
      optTpe,
      someTpe,
      noneTpe,
      "long long",
      "strtoll",
      T,
      T,
      st"${cTypeUp}_MIN",
      st"${cTypeUp}_MAX"
    )
    allHEntries = allHEntries :+ header
    allCEntries = allCEntries :+ impl
  }

  def genFloatConstructor(name: QName, optTpe: ST, someTpe: ST, noneTpe: ST, cType: String, cStrTo: String): Unit = {
    val (header, impl) = strToNum(name, optTpe, someTpe, noneTpe, cType, cStrTo, F, F, st"", st"")
    allHEntries = allHEntries :+ header
    allCEntries = allCEntries :+ impl
  }

  def genBConstructor(): Unit = {
    val optTpe = transpileType(AST.Typed.Name(AST.Typed.optionName, ISZ(AST.Typed.b)))
    val someTpe = transpileType(AST.Typed.Name(AST.Typed.someName, ISZ(AST.Typed.b)))
    val noneTpe = transpileType(AST.Typed.Name(AST.Typed.noneName, ISZ(AST.Typed.b)))
    val (header, impl) = strToB(optTpe, someTpe, noneTpe)
    allHEntries = allHEntries :+ header
    allCEntries = allCEntries :+ impl
  }

  @pure def transpileType(tpe: AST.Typed): ST = {
    return st"${typeNameMap.get(tpe).get}"
  }

  @pure def typeDecl(t: AST.Typed): ST = {
    val kind = typeKind(t)
    return if (t == AST.Typed.string) st"struct StaticString" else st"${typePrefix(kind)}${fingerprint(t)._1}"
  }

  @memoize def typeKind(t: AST.Typed): TypeKind.Type = {
    @pure def bitWidthKind(n: Z): TypeKind.Type = {
      n match {
        case z"8" => return TypeKind.Scalar8
        case z"16" => return TypeKind.Scalar16
        case z"32" => return TypeKind.Scalar32
        case z"64" => return TypeKind.Scalar64
        case _ => halt(s"Infeasible: $n")
      }
    }
    t match {
      case AST.Typed.b => return TypeKind.Scalar1
      case AST.Typed.c => return TypeKind.Scalar8
      case AST.Typed.z => bitWidthKind(config.defaultBitWidth)
      case AST.Typed.f32 => return TypeKind.Scalar32
      case AST.Typed.f64 => return TypeKind.Scalar64
      case AST.Typed.r => return TypeKind.R
      case AST.Typed.string => return TypeKind.Immutable
      case t: AST.Typed.Name =>
        if (t.ids == AST.Typed.isName) {
          return TypeKind.IS
        } else if (t.ids == AST.Typed.msName) {
          return TypeKind.MS
        } else {
          ts.typeHierarchy.typeMap.get(t.ids).get match {
            case info: TypeInfo.SubZ =>
              val bw = info.ast.bitWidth
              return bitWidthKind(if (bw == z"0") config.defaultBitWidth else bw)
            case _: TypeInfo.Enum => return TypeKind.Enum
            case info: TypeInfo.Adt =>
              return if (info.ast.isDatatype) if (info.ast.isRoot) TypeKind.ImmutableTrait else TypeKind.Immutable
              else if (info.ast.isRoot) TypeKind.MutableTrait
              else TypeKind.Mutable
            case info: TypeInfo.Sig =>
              return if (info.ast.isExt) TypeKind.Immutable
              else if (info.ast.isImmutable) TypeKind.ImmutableTrait
              else TypeKind.MutableTrait
            case _ => halt("Infeasible")
          }
        }
      case t: AST.Typed.Tuple =>
        for (targ <- t.args) {
          typeKind(targ) match {
            case TypeKind.Mutable => return TypeKind.Mutable
            case TypeKind.MutableTrait => return TypeKind.Mutable
            case TypeKind.MS => return TypeKind.Mutable
            case _ =>
          }
        }
        return TypeKind.Immutable
      case _ => return TypeKind.Immutable
    }
  }

  @pure def methodNameRes(receiverTypeOpt: Option[AST.Typed.Name], res: AST.ResolvedInfo.Method): ST = {
    return methodName(receiverTypeOpt, res.isInObject, res.owner, res.id, res.typeParams.isEmpty, res.tpeOpt.get)
  }

  @pure def methodNameTyped(receiverTypeOpt: Option[AST.Typed.Name], res: AST.Typed.Method): ST = {
    return methodName(receiverTypeOpt, res.isInObject, res.owner, res.name, res.typeParams.isEmpty, res.tpe)
  }

  @pure def methodName(
    receiverTypeOpt: Option[AST.Typed.Name],
    isInObject: B,
    owner: QName,
    id: String,
    noTypeParams: B,
    t: AST.Typed.Fun
  ): ST = {
    val r: ST =
      if (isInObject) {
        val ids: QName = ts.forwarding.get(owner) match {
          case Some(o) => o
          case _ => owner
        }
        if (noTypeParams) st"${mangleName(ids)}_${methodId(id)}"
        else st"${mangleName(ids)}_${methodId(id)}_${fprint(t)}"
      } else if (sNameSet.contains(owner)) {
        st"${transpileType(receiverTypeOpt.get)}_${methodId(id)}"
      } else if (noTypeParams) {
        id.native match {
          case "unary_+" => st"${transpileType(receiverTypeOpt.get)}__plus"
          case "unary_-" => st"${transpileType(receiverTypeOpt.get)}__minus"
          case "unary_!" => st"${transpileType(receiverTypeOpt.get)}__not"
          case "unary_~" => st"${transpileType(receiverTypeOpt.get)}__complement"
          case _ =>
            receiverTypeOpt match {
              case Some(receiverType) => st"${transpileType(receiverType)}_${methodId(id)}_"
              case _ => st"${mangleName(owner)}_${methodId(id)}"
            }
        }
      } else {
        receiverTypeOpt match {
          case Some(receiverType) => st"${transpileType(receiverType)}_${methodId(id)}_${fprint(t)}_"
          case _ => st"${mangleName(owner)}_${methodId(id)}_${fprint(t)}"
        }
      }
    return r
  }

  @pure def methodHeaderRes(receiverOpt: Option[AST.Typed.Name], res: AST.ResolvedInfo.Method): ST = {
    return methodHeader(
      receiverOpt,
      res.isInObject,
      res.owner,
      res.id,
      res.typeParams.isEmpty,
      res.tpeOpt.get,
      res.paramNames,
      ISZ()
    )
  }

  @pure def methodHeader(
    receiverOpt: Option[AST.Typed.Name],
    isInObject: B,
    owner: QName,
    id: String,
    noTypeParams: B,
    t: AST.Typed.Fun,
    paramNames: ISZ[String],
    closureVars: ISZ[ClosureVar]
  ): ST = {
    val name = methodName(receiverOpt, isInObject, owner, id, noTypeParams, t)
    val tpe = transpileType(t.ret)
    val (retType, retTypeDecl): (ST, ST) =
      if (isScalar(typeKind(t.ret)) || t.ret == AST.Typed.unit) (st"", tpe)
      else (st"$tpe result, ", st"void")
    val preParams: ST = receiverOpt match {
      case Some(receiver) => st"${retType}StackFrame caller, ${transpileType(receiver)} this"
      case _ => st"${retType}StackFrame caller"
    }
    val params: ST =
      if (paramNames.isEmpty) preParams
      else
        st"$preParams, ${(
          for (p <- ops.ISZOps(t.args).zip(paramNames))
            yield st"${transpileType(p._1)} ${localId(p._2)}",
          ", "
        )}"
    val cls: ST =
      if (closureVars.isEmpty) {
        st""
      } else {
        var cl = ISZ[ST]()
        for (cv <- closureVars) {
          val ptpe = transpileType(cv.t)
          if (isScalar(typeKind(cv.t))) {
            cl = cl :+ st"$ptpe *${localId(cv.id)}"
          } else {
            cl = cl :+ st"$ptpe ${localId(cv.id)}"
          }
        }
        st", ${(cl, ", ")}"
      }
    return st"$retTypeDecl $name($params$cls)"
  }

  def declString(): ST = {
    val tmp = freshTempName()
    val tmp2 = freshTempName()
    stmts = stmts :+ st"DeclNewString($tmp);"
    stmts = stmts :+ st"String $tmp2 = (String) &$tmp;"
    return tmp2
  }

  def freshTempName(): ST = {
    val r = st"t_$nextTempNum"
    nextTempNum = nextTempNum + 1
    return r
  }

  @pure def isControl(c: C): B = {
    return ('\u0000' <= c && c <= '\u001F') || ('\u007F' <= c && c <= '\u009F')
  }

  @pure def compiledKeyName(t: AST.Typed): QName = {
    t match {
      case t: AST.Typed.Name =>
        return if (t.args.isEmpty) t.ids else ops.ISZOps(t.ids).dropRight(1) :+ fingerprint(t)._1.render
      case t: AST.Typed.Tuple => return AST.Typed.sireumName :+ fingerprint(t)._1.render
      case t: AST.Typed.Fun => return AST.Typed.sireumName :+ fingerprint(t)._1.render
      case _ => halt("Infeasible")
    }
  }

  @pure def isNativeRes(res: AST.ResolvedInfo): B = {
    res match {
      case res: AST.ResolvedInfo.BuiltIn => return res.kind == AST.ResolvedInfo.BuiltIn.Kind.Native
      case _ => return F
    }
  }

  def escapeString(posOpt: Option[Position], s: String): ST = {
    val u8is = conversions.String.toU8is(s)
    val value = MSZ.create[String](u8is.size, "")
    for (i <- u8is.indices) {
      value(i) = escapeChar(posOpt, conversions.U32.toC(conversions.U8.toU32(u8is(i))))
    }
    return st"${(value, "")}"
  }

  def escapeChar(posOpt: Option[Position], c: C): String = {
    if (c <= '\u00FF') {
      c.native match {
        case '\u0000' => return "\\\u0000"
        case '\u0007' => return "\\a"
        case '\b' => return "\\b"
        case '\f' => return "\\f"
        case '\n' => return "\\n"
        case '\r' => return "\\r"
        case '\t' => return "\\t"
        case '\u000B' => return "\\v"
        case '\\' => return "\\\\"
        case '\u003F' => return "\\?"
        case '\'' => return "\\'"
        case '\"' => return "\\\""
        case _ =>
          return if (isControl(c))
            s"$escapeSep\\x${ops.COps.hex2c(c >>> '\u0004')}${ops.COps.hex2c(c & '\u000F')}$escapeSep"
          else c.string
      }
    } else {
      reporter.error(
        posOpt,
        transKind,
        "Static C translation does not support Unicode character literal (use String literal instead)."
      )
      return "\\?"
    }
  }

}