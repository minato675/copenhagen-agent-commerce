import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File
fun main(args: Array<String>) {
 val d = Disposer.newDisposable()
 try {
  val env = KotlinCoreEnvironment.createForProduction(d,CompilerConfiguration(),EnvironmentConfigFiles.JVM_CONFIG_FILES)
  val factory=KtPsiFactory(env.project,false)
  var errors=0; var count=0
  File(args[0]).walkTopDown().filter{it.isFile&&(it.extension=="kt"||it.extension=="kts")}.forEach{f->
   count++
   val tree=factory.createFile(f.name,f.readText())
   PsiTreeUtil.findChildrenOfType(tree,PsiErrorElement::class.java).forEach{err->
    errors++; val line=f.readText().take(err.textOffset).count{it=='\n'}+1
    println("${f.path}:$line: ${err.errorDescription}")
   }
  }
  println("Parsed $count Kotlin/Kotlin DSL files; syntax errors: $errors")
  if(errors>0) kotlin.system.exitProcess(1)
 }finally{Disposer.dispose(d)}
}
