::#! 2> /dev/null                                             #
@ 2>/dev/null # 2>nul & echo off & goto BOF                   #
if [ -f "$0.com" ] && [ "$0.com" -nt "$0" ]; then             #
  exec "$0.com" "$@"                                          #
fi                                                            #
rm -f "$0.com"                                                #
if [ -z ${SIREUM_HOME} ]; then                                #
  echo "Please set SIREUM_HOME env var"                       #
  exit -1                                                     #
fi                                                            #
exec ${SIREUM_HOME}/bin/sireum slang run -n "$0" "$@"         #
:BOF
setlocal
if not defined SIREUM_HOME (
  echo Please set SIREUM_HOME env var
  exit /B -1
)
set NEWER=False
if exist %~dpnx0.com for /f %%i in ('powershell -noprofile -executionpolicy bypass -command "(Get-Item %~dpnx0.com).LastWriteTime -gt (Get-Item %~dpnx0).LastWriteTime"') do @set NEWER=%%i
if "%NEWER%" == "True" goto native
del "%~dpnx0.com" > nul 2>&1
%SIREUM_HOME%\bin\sireum.bat slang run -n "%0" %*
exit /B %errorlevel%
:native
%~dpnx0.com %*
exit /B %errorlevel%
::!#
// #Sireum

import org.sireum._
import org.sireum.project.ProjectUtil._
import org.sireum.project.{JSON, Project}

val library = "library"
val test = "test"

val alir = "alir"

val transpilers = "transpilers"
val common = "common"
val c = "c"

val homeDir = Os.slashDir.up.canon

val commonShared = moduleShared(
  id = s"$transpilers-$common",
  baseDir = homeDir / common,
  sharedDeps = ISZ(alir),
  sharedIvyDeps = ISZ()
)

val (cShared, cJvm) = moduleSharedJvm(
  baseId = s"$transpilers-$c",
  baseDir = homeDir / c,
  sharedDeps = ISZ(commonShared.id),
  sharedIvyDeps = ISZ(),
  jvmDeps = ISZ(library, test),
  jvmIvyDeps = ISZ()
)

val project = Project.empty + commonShared + cShared + cJvm

println(JSON.fromProject(project, T))