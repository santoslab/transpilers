package org.sireum.transpiler

import _root_.java.io._
import _root_.java.nio.charset._
import _root_.java.util.zip._

import ammonite.ops._
import org.sireum._
import org.sireum.lang.FrontEnd
import org.sireum.lang.{ast => AST}
import org.sireum.lang.parser.Parser
import org.sireum.lang.tipe._
import org.sireum.message._

import scala.collection.JavaConverters._
import Cli._
import org.sireum.transpiler.c.StaticTranspiler
import org.sireum.transpilers.common.TypeSpecializer

object CTranspiler {

  val InvalidLibrary: Int = -1
  val InvalidMode: Int = -2
  val InvalidPath: Int = -3
  val InvalidFile: Int = -4
  val InvalidSources: Int = -5
  val InvalidSlangFiles: Int = -6
  val InvalidForceNames: Int = -7
  val InternalError: Int = -8
  val SavingError: Int = -9
  val LoadingError: Int = -10
  val PluginError: Int = -11
  val TranspilingError: Int = -12

  def run(o: CTranspilerOption): Int = {
    def readFile(f: File): (Option[String], String) = {
      val file = f.getCanonicalFile.getAbsoluteFile
      (Some(file.toURI.toASCIIString), read ! ammonite.ops.Path(file))
    }
    if (o.args.isEmpty && o.sourcepath.isEmpty) {
      println(o.help)
      println()
      println("Please either specify sourcepath or Slang files as argument")
      return 0
    }

    val loadFileOpt: Option[File] = if (o.load.nonEmpty) {
      val f = new File(o.load.get.value)
      if (!f.isFile) {
        eprintln("Invalid file to load type information from")
        return InvalidFile
      }
      Some(f)
    } else None()

    val saveFileOpt: Option[File] = if (o.save.nonEmpty) {
      val f = new File(o.save.get.value)
      if (f.exists && !f.isFile) {
        eprintln("Invalid file to save information to")
        return InvalidFile
      }
      Some(f)
    } else None()

    var start = 0l
    var used = 0l
    val rt = Runtime.getRuntime
    def startTime(): Unit = {
      start = System.currentTimeMillis
    }
    def stopTime(): Unit = {
      if (o.verbose) {
        val end = System.currentTimeMillis
        val newUsed = rt.totalMemory - rt.freeMemory
        if (newUsed > used) {
          used = newUsed
        }
        println(f"Time: ${end - start} ms, Memory: ${newUsed / 1024d / 1024d}%.2f MB")
      }
    }

    val begin = System.currentTimeMillis

    if (o.verbose && o.plugins.nonEmpty) {
      println("Loading plugins ...")
      startTime()
    }

    var plugins = ISZ[StaticTranspiler.ExtMethodTranspilerPlugin]()

    for (p <- o.plugins) {
      try {
        val c = Class.forName(p.value)
        plugins = plugins :+ c
          .getDeclaredConstructor()
          .newInstance()
          .asInstanceOf[StaticTranspiler.ExtMethodTranspilerPlugin]
      } catch {
        case _: Throwable =>
          eprintln(s"Could not load plugin: $p")
          return PluginError
      }
    }

    plugins = plugins ++ ISZ(
      StaticTranspiler.StringConversionsExtMethodTranspilerPlugin(),
      StaticTranspiler.NumberConversionsExtMethodTranspilerPlugin()
    )

    if (o.verbose) {
      if (o.plugins.nonEmpty) {
        stopTime()
        println()
      }

      if (o.args.nonEmpty) {
        println("Reading Slang file arguments ...")
        startTime()
      }
    }

    var slangFiles: ISZ[(String, (Option[String], String))] = ISZ()
    for (arg <- o.args) {
      val f = new File(arg.value)
      if (!f.exists) {
        eprintln(s"File $arg does not exist.")
        return InvalidFile
      } else if (!f.isFile) {
        eprintln(s"Path $arg is not a file.")
        return InvalidFile
      } else if (!f.getName.endsWith(".slang")) {
        eprintln(s"Can only accept .slang files as arguments")
        return InvalidFile
      }
      slangFiles = slangFiles :+ ((arg, readFile(f)))
    }

    if (o.verbose) {
      if (o.args.nonEmpty) {
        stopTime()
        println()
      }

      if (o.exts.nonEmpty) {
        println("Reading extension files ...")
        startTime()
      }
    }

    var exts: ISZ[StaticTranspiler.ExtFile] = ISZ()
    for (ext <- o.exts) {
      val f = new File(ext.value)
      if (!f.exists) {
        eprintln(s"File $ext does not exist.")
        return InvalidFile
      } else if (!f.isFile) {
        eprintln(s"Path $ext is not a file.")
        return InvalidFile
      } else if (!f.getName.endsWith(".c") && !f.getName.endsWith(".h")) {
        eprintln(s"Can only accept .h or .c files as extension files")
        return InvalidFile
      }
      val p = readFile(f)
      exts = exts :+ StaticTranspiler.ExtFile(p._1.get, p._2)
    }

    if (o.verbose) {
      if (o.exts.nonEmpty) {
        stopTime()
        println()
      }
      println("Reading sourcepath files ...")
      startTime()
    }

    var sources = ISZ[(Option[String], String)]()
    def collectFiles(f: File): Unit = {
      if (f.isDirectory) {
        for (file <- f.listFiles()) {
          collectFiles(file)
        }
      } else if (f.isFile) {
        if (f.getName.endsWith(".scala")) {
          var isSlang = F
          for (firstLine <- java.nio.file.Files.lines(f.toPath, StandardCharsets.UTF_8).limit(1).iterator.asScala) {
            isSlang = firstLine
              .replaceAllLiterally(" ", "")
              .replaceAllLiterally("\t", "")
              .replaceAllLiterally("\r", "")
              .contains("#Sireum")
          }
          if (isSlang) {
            sources = sources :+ readFile(f)
            if (o.verbose) println(s"Read ${f.getCanonicalPath}")
          }
        }
      }
    }

    for (p <- o.sourcepath) {
      val f = new File(p.value)
      if (!f.exists) {
        eprintln(s"Source path '$p' does not exist.")
        return InvalidPath
      } else {
        collectFiles(f)
      }
    }

    if (o.sourcepath.nonEmpty && sources.isEmpty) {
      eprintln("Did not find any sources in the specified sourcepath")
      return InvalidSources
    }
    stopTime()

    var th: TypeHierarchy = loadFileOpt match {
      case Some(loadFile) =>
        if (o.verbose) {
          println()
          println(s"Loading type information from ${loadFile.getPath} ...")
          startTime()
        }
        val data: ISZ[U8] = {
          val gis = new GZIPInputStream(new FileInputStream(loadFile))
          try {
            toIS(gis.bytes)
          } catch {
            case e: IOException =>
              eprintln(s"Could not load file: ${e.getMessage}")
              return LoadingError
          } finally gis.close()
        }
        CustomMessagePack.toTypeHierarchy(data) match {
          case Either.Left(thl) => thl
          case Either.Right(errorMsg) =>
            eprintln(s"Loading error at offset ${errorMsg.offset}: ${errorMsg.message}")
            return LoadingError
        }
      case _ =>
        if (o.verbose) {
          println()
          println(s"Parsing, resolving, type outlining, and type checking Slang library files ...")
          startTime()
        }

        val (thl, rep) = {
          val p = FrontEnd.checkedLibraryReporter
          (p._1.typeHierarchy, p._2)
        }
        if (rep.hasIssue) {
          rep.printMessages()
          return InvalidLibrary
        }
        thl
    }
    stopTime()

    val reporter = Reporter.create

    if (o.verbose) {
      println()
      println("Parsing and resolving Slang sourcepath programs ...")
      startTime()
    }

    val t = FrontEnd.parseProgramAndGloballyResolve(sources, th.nameMap, th.typeMap)
    if (t._1.hasIssue) {
      t._1.printMessages()
      return InvalidSources
    }
    stopTime()

    if (o.verbose) {
      println()
      println("Building type hierarchy of Slang sourcepath programs ...")
      startTime()
    }

    th = TypeHierarchy.build(th(nameMap = t._3, typeMap = t._4), reporter)
    if (reporter.hasIssue) {
      reporter.printMessages()
      return InvalidSources
    }
    stopTime()

    if (o.verbose) {
      println()
      println("Type outlining Slang sourcepath programs ...")
      startTime()
    }

    th = TypeOutliner.checkOutline(th, reporter)
    if (reporter.hasIssue) {
      reporter.printMessages()
      return InvalidSources
    }
    stopTime()

    if (o.verbose) {
      println()
      println("Type checking Slang sourcepath programs ...")
      startTime()
    }

    th = TypeChecker.checkComponents(th, th.nameMap, th.typeMap, reporter)

    if (reporter.hasIssue) {
      reporter.printMessages()
      return InvalidSources
    }
    stopTime()

    if (o.verbose) {
      println()
      println("Sanity checking computed symbol and type information of Slang library files and sourcepath programs ...")
      startTime()
    }

    PostTipeAttrChecker.checkNameTypeMaps(th.nameMap, th.typeMap, reporter)

    if (reporter.hasIssue) {
      reporter.printMessages()
      return InternalError
    }
    stopTime()

    saveFileOpt match {
      case Some(saveFile) =>
        if (o.verbose) {
          println()
          println(s"Saving type information to ${saveFile.getPath} ...")
          startTime()
        }

        val (buf, length) = fromIS(CustomMessagePack.fromTypeHierarchy(th))
        val gos = new GZIPOutputStream(new FileOutputStream(saveFile))
        try gos.write(buf, 0, length)
        catch {
          case e: IOException =>
            eprintln(s"Could not save file: ${e.getMessage}")
            return SavingError
        } finally gos.close()

        stopTime()
      case _ =>
    }

    var thOpt: Option[TypeHierarchy] = Some(th)
    var entryPoints = ISZ[TypeSpecializer.EntryPoint]()
    for (slangFile <- slangFiles) {
      if (o.verbose) {
        println()
        println(s"Parsing, resolving, type outlining, and type checking ${slangFile._1} ...")
        startTime()
      }

      Parser.parseTopUnit[AST.TopUnit.Program](slangFile._2._2, F, T, F, slangFile._2._1, reporter) match {
        case Some(p: AST.TopUnit.Program) =>
          val p2 = FrontEnd.checkWorksheet(thOpt, p, reporter)
          if (reporter.hasIssue) {
            reporter.printMessages()
            return InvalidSlangFiles
          }
          stopTime()

          if (o.verbose) {
            println()
            println(s"Sanity checking computed symbol and type information of ${slangFile._1} ...")
            startTime()
          }

          PostTipeAttrChecker.checkProgram(p2._2, reporter)

          if (reporter.hasIssue) {
            reporter.printMessages()
            return InternalError
          }

          entryPoints = entryPoints :+ TypeSpecializer.EntryPoint.Worksheet(p2._2)
          thOpt = Some(p2._1)

        case Some(_) => eprintln(s"File '${slangFile._1}' does not contain a Slang program")
        case _ =>
      }
      stopTime()
    }

    if (o.verbose) {
      println()
      println(s"Specializing programs ...")
      startTime()
    }

    var forwardingMap = HashMap.empty[ISZ[String], ISZ[String]]
    for (p <- o.forwarding) {
      val Array(key, value) = p.value.split('=')
      forwardingMap = forwardingMap + ISZ(key.split('.').map(s => String(s.trim)): _*) ~> ISZ(
        value.split('.').map(s => String(s.trim)): _*
      )
    }

    for (app <- o.apps) {
      entryPoints = entryPoints :+ TypeSpecializer.EntryPoint.App(ISZ(app.value.split('.').map(String(_)): _*))
    }

    val tsr = TypeSpecializer.specialize(thOpt.get, entryPoints, forwardingMap, reporter)

    reporter.printMessages()
    if (reporter.hasIssue) {
      return InternalError
    }
    stopTime()

    if (o.verbose) {
      println()
      println("Transpiling to C ...")
      startTime()
    }

    var customArraySizes = HashMap.empty[AST.Typed, Z]
    for (p <- o.customArraySizes) {
      try {
        val Array(key, value) = p.value.split('=')
        val num = Z(value).get
        if (num <= 0) {
          eprintln(s"Custom sequence size should be positive: $p")
          return TranspilingError
        }
        val e = Parser.parseExp[AST.Exp.Select](s"o[$key]")
        e.targs(0) match {
          case t: AST.Type.Named =>
            t.name.ids.map(_.value.value) match {
              case ISZ("MS") if t.typeArgs.size.toInt == 2 =>
                customArraySizes = customArraySizes + AST.Typed
                  .Name(AST.Typed.msName, ISZ(toTyped(t.typeArgs(0)), toTyped(t.typeArgs(1)))) ~> num
              case ISZ("IS") if t.typeArgs.size.toInt == 2 =>
                customArraySizes = customArraySizes + AST.Typed
                  .Name(AST.Typed.isName, ISZ(toTyped(t.typeArgs(0)), toTyped(t.typeArgs(1)))) ~> num
              case ISZ("ISZ") if t.typeArgs.size.toInt == 1 =>
                customArraySizes = customArraySizes + AST.Typed
                  .Name(AST.Typed.isName, ISZ(AST.Typed.z, toTyped(t.typeArgs(0)))) ~> num
              case ISZ("MSZ") if t.typeArgs.size.toInt == 1 =>
                customArraySizes = customArraySizes + AST.Typed
                  .Name(AST.Typed.msName, ISZ(AST.Typed.z, toTyped(t.typeArgs(0)))) ~> num
              case ISZ("ZS") if t.typeArgs.size.toInt == 0 =>
                customArraySizes = customArraySizes + AST.Typed
                  .Name(AST.Typed.msName, ISZ(AST.Typed.z, AST.Typed.z)) ~> num
              case _ => throw new Exception
            }
          case _ => throw new Exception
        }
      } catch {
        case _: Throwable =>
          eprintln(s"Could not recognize custom sequence size configuration: $p")
          return TranspilingError
      }
    }

    val config = StaticTranspiler.Config(
      projectName = o.projectName.getOrElse("main"),
      lineNumber = o.line,
      fprintWidth = o.fingerprint,
      defaultBitWidth = o.bitWidth,
      maxStringSize = o.maxStringSize,
      maxArraySize = o.maxArraySize,
      customArraySizes = customArraySizes,
      extMethodTranspilerPlugins = plugins,
      exts = exts,
      forLoopOpt = o.unroll
    )

    val trans = StaticTranspiler(config, tsr)
    val r = trans.transpile(reporter)

    reporter.printMessages()
    if (reporter.hasIssue) {
      return TranspilingError
    }
    stopTime()

    if (o.verbose) {
      println()
      println("Writing generated files ...")
      startTime()
    }

    val resultDir = Path(new File(o.output.get.value).getCanonicalFile.getAbsolutePath)
    rm ! resultDir
    mkdir ! resultDir

    for (e <- r.files.entries) {
      val path = e._1
      var f = resultDir
      for (segment <- path) {
        f = f / segment.value
      }
      mkdir ! f / up
      write.over(f, e._2.render.value)
      println(s"Wrote $f")
    }
    stopTime()

    if (o.verbose) {
      val newUsed = rt.totalMemory - rt.freeMemory
      if (newUsed > used) {
        used = newUsed
      }
      println()
      println(
        f"Ok! Total time: ${(System.currentTimeMillis - begin) / 1000d}%.2f s, Max memory: ${used / 1024d / 1024d}%.2f MB"
      )
    }

    return 0
  }

  def toIS(data: Array[Byte]): ISZ[U8] = {
    new IS(Z, data, data.length, U8.Boxer)
  }

  def fromIS(data: ISZ[U8]): (Array[Byte], Int) = {
    return (data.data.asInstanceOf[Array[Byte]], data.size.toInt)
  }

  implicit class GZIS(val gzis: GZIPInputStream) extends AnyVal {

    def bytes: Array[Byte] = {
      val bos = new ByteArrayOutputStream
      val buffer = new Array[Byte](16384)
      var n = gzis.read(buffer)
      while (n > -1) {
        bos.write(buffer, 0, n)
        n = gzis.read(buffer)
      }
      gzis.close()
      bos.toByteArray
    }
  }

  def toTyped(t: AST.Type): AST.Typed = {
    t match {
      case t: AST.Type.Named => AST.Typed.Name(t.name.ids.map(_.value), t.typeArgs.map(toTyped))
      case t: AST.Type.Tuple => AST.Typed.Tuple(t.args.map(toTyped))
      case t: AST.Type.Fun => AST.Typed.Fun(t.isPure, t.isByName, t.args.map(toTyped), toTyped(t.ret))
    }
  }
}
