import org.sireum._
import org.sireum.transpiler.{CTranspiler, Cli}
import java.io._

object BuildingControlGenApp extends scala.App {
  if (args.length != 2) {
    println("Usage: BuildingControlApp <slang-embedded-path> <output-path>")
  } else {
    BuildingControlApp.transpile(T, args, "building-control-gen")
  }
}

object BuildingControlGenShmApp extends scala.App {
  if (args.length != 2) {
    println("Usage: BuildingControlShmApp <slang-embedded-path> <output-path>")
  } else {
    BuildingControlApp.transpile(F, args, "building-control-gen-shm")
  }
}

object BuildingControlGenPeriodicApp extends scala.App {
  if (args.length != 2) {
    println("Usage: BuildingControlApp <slang-embedded-path> <output-path>")
  } else {
    BuildingControlApp.transpile(T, args, "building-control-gen-periodic")
  }
}

object BuildingControlGenMixedApp extends scala.App {
  if (args.length != 2) {
    println("Usage: BuildingControlApp <slang-embedded-path> <output-path>")
  } else {
    BuildingControlApp.transpile(T, args, "building-control-gen-mixed")
  }
}

object BuildingControlApp {

  def transpile(hasAEP: B, args: Array[Predef.String], example: Predef.String): Unit = {
    val slangPath = Os.path(args(0))
    val out = Os.path(args(1)) / example
    val dir = Os.path(implicitly[sourcecode.File].value).up
    val pkg = example.replaceAllLiterally("-", "_")
    val extPath = slangPath / example / "src" / "c" / "ext"
    var extFiles = Vector[Os.Path]()
    for (f <- extPath.list if f.name.value.endsWith(".c") || f.name.value.endsWith(".h")) {
      extFiles = extFiles :+ f
    }
    val readme = dir / pkg / "readme.md"

    out.removeAll()

    (out / "src").mkdirAll()
    (slangPath / example / "src" / "aadl").copyOverTo(out / "src" / "aadl")
    (slangPath / example / "src" / "main").copyOverTo(out / "src" / "scala")
    readme.copyOverTo(out / "readme.md")

    (out / "bin" / "compile-mac.sh").writeOver(s"""#!/usr/bin/env bash
                                            |set -e
                                            |export SCRIPT_HOME=$$( cd "$$( dirname "$$0" )" &> /dev/null && pwd )
                                            |cd $$SCRIPT_HOME
                                            |mkdir -p mac
                                            |mkdir -p $$SCRIPT_HOME/../src/c/mac
                                            |cd $$SCRIPT_HOME/../src/c/mac
                                            |cmake $$CMAKE_OPTS -DCMAKE_BUILD_TYPE=Release ..
                                            |make $$MAKE_ARGS
                                            |mv *_App $$SCRIPT_HOME/mac/
                                            |${if (hasAEP) "mv *_AEP $SCRIPT_HOME/mac/" else ""}
                                            |mv Main $$SCRIPT_HOME/mac/""".stripMargin)

    (out / "bin" / "compile-linux.sh").writeOver(s"""#!/usr/bin/env bash
                                              |set -e
                                              |export SCRIPT_HOME=$$( cd "$$( dirname "$$0" )" &> /dev/null && pwd )
                                              |cd $$SCRIPT_HOME
                                              |mkdir -p linux
                                              |mkdir -p $$SCRIPT_HOME/../src/c/linux
                                              |cd $$SCRIPT_HOME/../src/c/linux
                                              |cmake $$CMAKE_OPTS -DCMAKE_BUILD_TYPE=Release ..
                                              |make $$MAKE_ARGS
                                              |mv *_App $$SCRIPT_HOME/linux/
                                              |${if (hasAEP) "mv *_AEP $SCRIPT_HOME/linux/" else ""}
                                              |mv Main $$SCRIPT_HOME/linux/""".stripMargin)

    (out / "bin" / "compile-cygwin.sh").writeOver(s"""#!/usr/bin/env bash
                                               |set -e
                                               |export SCRIPT_HOME=$$( cd "$$( dirname "$$0" )" &> /dev/null && pwd )
                                               |cd $$SCRIPT_HOME
                                               |mkdir -p win
                                               |mkdir -p $$SCRIPT_HOME/../src/c/win
                                               |cd $$SCRIPT_HOME/../src/c/win
                                               |cmake $$CMAKE_OPTS -DCMAKE_BUILD_TYPE=Release ..
                                               |make $$MAKE_ARGS
                                               |mv *.exe $$SCRIPT_HOME/win/""".stripMargin)

    (out / "bin" / "run-mac.sh").writeOver(
      if (hasAEP)
        """#!/usr/bin/env bash
          |set -e
          |export SCRIPT_HOME=$( cd "$( dirname "$0" )" &> /dev/null && pwd )
          |cd $SCRIPT_HOME
          |mac/TempControl_i_AEP 2> /dev/null &
          |mac/Fan_i_AEP 2> /dev/null &
          |open -a Terminal mac/TempControl_i_App
          |open -a Terminal mac/TempSensor_i_App
          |open -a Terminal mac/Fan_i_App
          |read -p "Press enter to start ..."
          |mac/Main""".stripMargin
      else
        """#!/usr/bin/env bash
          |set -e
          |export SCRIPT_HOME=$( cd "$( dirname "$0" )" &> /dev/null && pwd )
          |cd $SCRIPT_HOME
          |open -a Terminal mac/TempControl_i_App
          |open -a Terminal mac/TempSensor_i_App
          |open -a Terminal mac/Fan_i_App
          |read -p "Press enter to start ..."
          |mac/Main""".stripMargin
    )

    (out / "bin" / "run-linux.sh").writeOver(
      if (hasAEP)
        """#!/usr/bin/env bash
          |set -e
          |export SCRIPT_HOME=$( cd "$( dirname "$0" )" &> /dev/null && pwd )
          |cd $SCRIPT_HOME
          |linux/TempControl_i_AEP 2> /dev/null &
          |linux/Fan_i_AEP 2> /dev/null &
          |x-terminal-emulator -e sh -c linux/TempControl_i_App &
          |x-terminal-emulator -e sh -c linux/TempSensor_i_App &
          |x-terminal-emulator -e sh -c linux/Fan_i_App &
          |read -p "Press enter to start ..."
          |linux/Main""".stripMargin
      else
        """#!/usr/bin/env bash
          |set -e
          |export SCRIPT_HOME=$( cd "$( dirname "$0" )" &> /dev/null && pwd )
          |cd $SCRIPT_HOME
          |x-terminal-emulator -e sh -c linux/TempControl_i_App &
          |x-terminal-emulator -e sh -c linux/TempSensor_i_App &
          |x-terminal-emulator -e sh -c linux/Fan_i_App &
          |read -p "Press enter to start ..."
          |linux/Main""".stripMargin
    )

    (out / "bin" / "run-cygwin.sh").writeOver(
      if (hasAEP)
        """#!/usr/bin/env bash
          |set -e
          |export SCRIPT_HOME=$( cd "$( dirname "$0" )" &> /dev/null && pwd )
          |cd $SCRIPT_HOME
          |win/TempControl_i_AEP.exe 2> /dev/null &
          |win/Fan_i_AEP.exe 2> /dev/null &
          |cygstart mintty /bin/bash -c win/TempControl_i_App.exe &
          |cygstart mintty /bin/bash -c win/TempSensor_i_App.exe &
          |cygstart mintty /bin/bash -c win/Fan_i_App.exe &
          |read -p "Press enter to start ..."
          |win/Main.exe""".stripMargin
      else
        """#!/usr/bin/env bash
          |set -e
          |export SCRIPT_HOME=$( cd "$( dirname "$0" )" &> /dev/null && pwd )
          |cd $SCRIPT_HOME
          |win/TempControl_i_AEP.exe 2> /dev/null &
          |win/Fan_i_AEP.exe 2> /dev/null &
          |cygstart mintty /bin/bash -c win/TempControl_i_App.exe &
          |cygstart mintty /bin/bash -c win/TempSensor_i_App.exe &
          |cygstart mintty /bin/bash -c win/Fan_i_App.exe &
          |read -p "Press enter to start ..."
          |win/Main.exe""".stripMargin
    )

    (out / "bin" / "stop.sh").write("""#!/usr/bin/env bash
                                    |APPS="TempControl TempSensor Fan"
                                    |for APP in ${APPS}; do
                                    |  pkill $APP
                                    |  pkill -9 $APP
                                    |done
                                    |ME=`whoami`
                                    |IPCS_Q=`ipcs -q | egrep "[0-9a-f]+[0-9]+" | grep $ME | cut -f2 -d" "`
                                    |for id in $IPCS_Q; do
                                    |  ipcrm -q $id
                                    |done
                                    |IPCS_Q=`ipcs -m | egrep "[0-9a-f]+[0-9]+" | grep $ME | grep 666 | cut -f2 -d" "`
                                    |for id in $IPCS_Q; do
                                    |  ipcrm -m $id
                                    |done
                                    |IPCS_Q=`ipcs -s | egrep "[0-9a-f]+[0-9]+" | grep $ME | grep 666 | cut -f2 -d" "`
                                    |for id in $IPCS_Q; do
                                    |  ipcrm -s $id
                                    |done
                                    |ipcs""".stripMargin)

    for (f <- (out / "bin").list if f.name.value.endsWith(".sh")) {
      f.chmod("+x")
    }

    Cli(File.pathSeparatorChar).parseSireum(
      ISZ(
        "transpiler",
        "c",
        "-n",
        example,
        "--verbose",
        "--sourcepath",
        s"${out / "src" / "scala"}",
        "--bits",
        "32",
        "--string-size",
        "256",
        "--sequence-size",
        "16",
        "--sequence",
        "ISZ[org.sireumString]=2",
        "--constants",
        s"art.Art.maxComponents=3,art.Art.maxPorts=${if (hasAEP) "14" else "11"}",
        "--apps",
        if (hasAEP)
          s"$pkg.Fan_i_AEP,$pkg.Fan_i_App,$pkg.TempControl_i_AEP,$pkg.TempControl_i_App,$pkg.TempSensor_i_App,$pkg.Main"
        else s"$pkg.Fan_i_App,$pkg.TempControl_i_App,$pkg.TempSensor_i_App,$pkg.Main",
        "--forward",
        s"art.ArtNative=$pkg.ArtNix,$pkg.Platform=$pkg.PlatformNix",
        "--exts",
        extFiles.mkString(":"),
        "--output-dir",
        (out / "src" / "c").string
      ),
      0
    ) match {
      case Some(o: Cli.CTranspilerOption) =>
        val baos = new ByteArrayOutputStream()
        val sysOut = System.out
        val sysErr = System.err
        val outs = new OutputStream {
          override def write(b: Int): Unit = {
            sysOut.write(b)
            baos.write(b)
          }
        }
        val errs = new OutputStream {
          override def write(b: Int): Unit = {
            sysErr.write(b)
            baos.write(b)
          }
        }
        System.setOut(new PrintStream(outs))
        System.setErr(new PrintStream(errs))
        CTranspiler.run(o)
        val s = new java.lang.String(baos.toByteArray)
        (out / "transpiler.log").writeOver(s)
      case Some(_: Cli.HelpOption) => sys.exit(0)
      case _ => sys.exit(-1)
    }

    val zipFilename = s"${out.name}.zip"
    (out.up / zipFilename).removeAll()
    Os.proc(ISZ("zip", "-qro", zipFilename, out.name)).at(out.up).console.runCheck()
  }

}
