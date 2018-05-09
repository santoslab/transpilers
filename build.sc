import $file.runtime.Runtime
import $file.slang.Slang
import $file.alir.Alir
import $file.Transpilers
import ammonite.ops.up

object runtime extends mill.Module {

  object macros extends Runtime.Module.Macros

  object library extends Runtime.Module.Library {

    final override val macrosObject = macros

  }

}

object slang extends mill.Module {

  object ast extends Slang.Module.Ast {

    final override val libraryObject = runtime.library

  }

  object parser extends Slang.Module.Parser {

    final override val astObject = ast

  }

  object tipe extends Slang.Module.Tipe {

    final override val astObject = ast

  }

  object frontend extends Slang.Module.FrontEnd {

    final override val parserObject = parser

    final override val tipeObject = tipe

  }

}

object alir extends Alir.Module {

  override val frontEndObject = slang.frontend

}

object transpilers extends mill.Module {

  final override val millSourcePath = super.millSourcePath / up

  object common extends Transpilers.Module.Common {

    override val alirObject = alir

  }

  object c extends Transpilers.Module.C {

    override val commonObject = common

  }

  object cli extends Transpilers.Module.Cli {

    override val cObject = c

  }

}
