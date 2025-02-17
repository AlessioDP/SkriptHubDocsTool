package net.skripthub.docstool.documentation

import ch.njol.skript.classes.Changer
import ch.njol.skript.classes.ClassInfo
import ch.njol.skript.config.validate.NodeValidator
import ch.njol.skript.config.validate.SectionValidator
import ch.njol.skript.doc.*
import ch.njol.skript.lang.ExpressionInfo
import ch.njol.skript.lang.SkriptEventInfo
import ch.njol.skript.lang.SyntaxElementInfo
import ch.njol.skript.lang.function.JavaFunction
import ch.njol.skript.registrations.Classes
import net.skripthub.docstool.modals.DocumentationEntryNode
import net.skripthub.docstool.modals.SyntaxData
import net.skripthub.docstool.utils.EventValuesGetter
import net.skripthub.docstool.utils.ReflectionUtils
import org.bukkit.event.Cancellable
import java.lang.reflect.Field
import java.util.*


class GenerateSyntax {
    companion object {
        fun generateSyntaxFromEvent(info: SkriptEventInfo<*>, getter: EventValuesGetter?) : SyntaxData? {
            if(info.description != null && info.description!!.contentEquals(SkriptEventInfo.NO_DOC)){
                return null
            }
            val data = SyntaxData()
            data.name = info.getName()
            data.id = info.id
            if (info.documentationID != null) {
                data.id = info.documentationID
            }
            data.description = removeHTML(info.description as? Array<String>)
            data.examples = cleanExamples(info.examples as Array<String>?)
            data.patterns = cleanupSyntaxPattern(info.patterns)
            if (data.patterns != null && data.name != null && data.name!!.startsWith("On ")) {
                for (x in 0 until data.patterns!!.size)
                    data.patterns!![x] = "[on] " + data.patterns!![x]
            }

            val sinceString = removeHTML(info.since)
            if (sinceString != null){
                data.since = arrayOf(sinceString)
            }

            for (c in info.events)
                if (Cancellable::class.java.isAssignableFrom(c)) {
                    data.cancellable = java.lang.Boolean.TRUE
                } else {
                    data.cancellable = java.lang.Boolean.FALSE
                    break
                }

            data.requiredPlugins = info.requiredPlugins as Array<String>?

            if (getter != null) {
                val classes = getter.getEventValues(info.events)
                if (classes == null || classes.isEmpty())
                    return null
                val time = arrayOf("past event-", "event-", "future event-")
                val times = ArrayList<String>()
                for (x in classes.indices)
                    (0 until classes[x].size)
                            .mapNotNull { Classes.getSuperClassInfo(classes[x][it]) }
                            .mapTo(times) { time[x] + it.codeName }
                data.eventValues = times.toTypedArray()
            }

            data.entries = getEntiresFromSkriptEventInfo(info)

            return data
        }

        fun generateSyntaxFromSyntaxElementInfo(info: SyntaxElementInfo<*>): SyntaxData? {
            val data = SyntaxData()
            val syntaxInfoClass = info.c
            if (syntaxInfoClass.isAnnotationPresent(NoDoc::class.java))
                return null
            if (syntaxInfoClass.isAnnotationPresent(Name::class.java))
                data.name = syntaxInfoClass.getAnnotation(Name::class.java).value
            if (data.name == null || data.name!!.isEmpty())
                data.name = syntaxInfoClass.simpleName
            data.id = syntaxInfoClass.simpleName
            if (syntaxInfoClass.isAnnotationPresent(DocumentationId::class.java)){
                data.id = syntaxInfoClass.getAnnotation(DocumentationId::class.java).value
            }
            if (syntaxInfoClass.isAnnotationPresent(Description::class.java))
                data.description = removeHTML(syntaxInfoClass.getAnnotation(Description::class.java).value)
            if (syntaxInfoClass.isAnnotationPresent(Examples::class.java))
                data.examples = cleanExamples(syntaxInfoClass.getAnnotation(Examples::class.java).value)
            data.patterns = cleanupSyntaxPattern(info.patterns)
            if (syntaxInfoClass.isAnnotationPresent(Since::class.java)) {
                val sinceString = removeHTML(syntaxInfoClass.getAnnotation(Since::class.java).value)
                if (sinceString != null) {
                    data.since = arrayOf(sinceString)
                }
            }
            if (syntaxInfoClass.isAnnotationPresent(RequiredPlugins::class.java))
                data.requiredPlugins = syntaxInfoClass.getAnnotation(RequiredPlugins::class.java).value

            data.entries = getEntiresFromSkriptElementInfo(info)

            return data
        }

        fun generateSyntaxFromExpression(info: ExpressionInfo<*, *>, classes: Array<Class<*>?>): SyntaxData? {
            val data = generateSyntaxFromSyntaxElementInfo(info) ?: return null
            val ci = Classes.getSuperClassInfo(info.returnType)
            if (ci != null)
                data.returnType = if (ci.docName == null || ci.docName!!.isEmpty()) ci.codeName else ci.docName
            else
                data.returnType = "Object"
            val array = ArrayList<String>()
            val expr = ReflectionUtils.newInstance(info.c)
            try {
                for (mode in Changer.ChangeMode.values()) {
                    if (Changer.ChangerUtils.acceptsChange(expr, mode, *classes))
                        array.add(mode.name.toLowerCase().replace('_', ' '))
                }
            } catch (e: Throwable) {
                array.add("unknown")
            }

            data.changers = array.toTypedArray()
            return data
        }

        fun generateSyntaxFromClassInfo(info: ClassInfo<*>): SyntaxData? {
            if (info.docName != null && info.docName.equals(ClassInfo.NO_DOC))
                return null
            val data = SyntaxData()
            if (info.docName != null){
                data.name = info.docName
            } else {
                data.name = info.codeName
            }
            data.id = info.c.simpleName
            if (data.id.equals("Type")) {
                data.id = data.id + data.name?.replace(" ", "")
            }
            data.description = removeHTML(info.description as? Array<String>)
            data.examples = cleanExamples(info.examples as? Array<String>)
            data.usage = removeHTML(info.usage as? Array<String>)
            val sinceString = removeHTML(info.since)
            if (sinceString != null){
                data.since = arrayOf(sinceString)
            }

            if (info.userInputPatterns != null && info.userInputPatterns!!.isNotEmpty()) {
                val size = info.userInputPatterns!!.size
                data.patterns = Array (size) { _ -> "" }
                var x = 0
                for (p in info.userInputPatterns!!) {
                    data.patterns!![x++] = p!!.pattern()
                            .replace("\\((.+?)\\)\\?".toRegex(), "[$1]")
                            .replace("(.)\\?".toRegex(), "[$1]")
                }
            } else {
                data.patterns = Array (1) { _ -> info.codeName }
            }
            return data
        }

        fun generateSyntaxFromFunctionInfo(info: JavaFunction<*>): SyntaxData? {
            val data = SyntaxData()
            data.name = info.name
            data.id = "function_" + info.name
            data.description = removeHTML(info.description as? Array<String>)
            data.examples = cleanExamples(info.examples as? Array<String>)
            val sb = StringBuilder()
            sb.append(info.name).append("(")
            if (info.parameters != null) {
                var index = 0
                for (p in info.parameters) {
                    if (index++ != 0)
                    //Skip the first parameter
                        sb.append(", ")
                    sb.append(p)
                }
            }
            sb.append(")")
            data.patterns = cleanupSyntaxPattern(arrayOf(sb.toString()))
            val sinceString = removeHTML(info.since)
            if (sinceString != null){
                data.since = arrayOf(sinceString)
            }
            val infoReturnType = info.returnType
            if (infoReturnType != null){
                data.returnType = if (infoReturnType.docName == null || infoReturnType.docName!!.isEmpty())
                    infoReturnType.codeName
                else
                    infoReturnType.docName
            }
            return data
        }

        private fun getEntiresFromSkriptElementInfo(info: SyntaxElementInfo<*>) : Array<DocumentationEntryNode>? {
            // See if the class has a SectionValidator and try to pull that out to use as the source of truth.
            val fields = info.elementClass.declaredFields;
            for (field in fields) {
                if (field.type.isAssignableFrom(SectionValidator::class.java)) {
                    try {
                        // pull nodes out of sectionValidator
                        field.isAccessible = true
                        val sectionValidator : SectionValidator = field.get(null) as? SectionValidator ?: break
                        val nodesField: Field = sectionValidator.javaClass.getDeclaredField("nodes")
                        nodesField.isAccessible = true
                        val entryNodes = nodesField[sectionValidator] as HashMap<String?, Any?>

                        // Build up entriesArray
                        val entriesArray : MutableList<DocumentationEntryNode> = mutableListOf()
                        for ((name, node) in entryNodes) {

                            if (name == null) {
                                continue;
                            }

                            var isSection: Boolean
                            var isRequired: Boolean

                            // See if its optional
                            var field: Field = node!!.javaClass.getDeclaredField("optional")
                            field.isAccessible = true
                            isRequired = !(field[node] as? Boolean)!!

                            // See if this is a section
                            field = node.javaClass.getDeclaredField("v")
                            field.isAccessible = true
                            val nodeValidator = field[node] as NodeValidator
                            isSection = nodeValidator is SectionValidator

                            entriesArray.add(DocumentationEntryNode(name, isRequired, isSection))
                        }

                        // Only use the first SectionValidator we find.
                        return entriesArray.toTypedArray()
                    } catch (ex: Exception) {
                        ex.printStackTrace();
                        return null;
                    }
                }
            }

            return null;
        }

        private fun getEntiresFromSkriptEventInfo(info: SkriptEventInfo<*>) : Array<DocumentationEntryNode>? {
            // See if the class has a SectionValidator and try to pull that out to use as the source of truth.
            val fields = info.elementClass.declaredFields;
            for (field in fields) {
                if (field.type.isAssignableFrom(SectionValidator::class.java)) {
                    try {
                        // pull nodes out of sectionValidator
                        field.isAccessible = true
                        val sectionValidator : SectionValidator = field.get(null) as? SectionValidator ?: break
                        val nodesField: Field = sectionValidator.javaClass.getDeclaredField("nodes")
                        nodesField.isAccessible = true
                        val entryNodes = nodesField[sectionValidator] as HashMap<String?, Any?>

                        // Build up entriesArray
                        val entriesArray : MutableList<DocumentationEntryNode> = mutableListOf()
                        for ((name, node) in entryNodes) {

                            if (name == null) {
                                continue;
                            }

                            var isSection: Boolean
                            var isRequired: Boolean

                            // See if its optional
                            var field: Field = node!!.javaClass.getDeclaredField("optional")
                            field.isAccessible = true
                            isRequired = !(field[node] as? Boolean)!!

                            // See if this is a section
                            field = node.javaClass.getDeclaredField("v")
                            field.isAccessible = true
                            val nodeValidator = field[node] as NodeValidator
                            isSection = nodeValidator is SectionValidator

                            entriesArray.add(DocumentationEntryNode(name, isRequired, isSection))
                        }

                        // Only use the first SectionValidator we find.
                        return entriesArray.toTypedArray()
                    } catch (ex: Exception) {
                        ex.printStackTrace();
                        return null;
                    }
                }
            }

            return null;
        }

        private fun cleanupSyntaxPattern(patterns: Array<String>): Array<String>{
            if(patterns.isEmpty()){
                return patterns
            }
            for (i in 0 until patterns.size){
                patterns[i] = patterns[i]
                        .replace("""\\([()])""".toRegex(), "$1")
                        .replace("""-?\d+¦""".toRegex(), "")
                        .replace("""-?\d+Â¦""".toRegex(), "")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("""%-(.+?)%""".toRegex()) {
                            match -> match.value.replace("-", "")
                        }
                        .replace("""%~(.+?)%""".toRegex()) {
                            match -> match.value.replace("~", "")
                        }
                        .replace("()", "")
                        .replace("""@-\d""".toRegex(), "")
                        .replace("""@\d""".toRegex(), "")
                        .replace("""\d¦""".toRegex(), "")
            }
            return patterns
        }

        private fun removeHTML(description: Array<String>?): Array<String>{
            if(description == null || description.isEmpty()){
                return emptyArray()
            }
            for (i in 0 until description.size){
                description[i] = this.removeHTML(description[i])!!
            }
            return description
        }

        private fun cleanExamples(examples: Array<String>?): Array<String>?{
            if (examples == null || examples.isEmpty()){
                return examples
            }
            if (examples.size == 1 && examples[0].isEmpty()){
                return null
            }
            return examples
        }

        private fun removeHTML(string: String?): String?{
            if(string.isNullOrEmpty()){
                return string
            }
            return string.replace("""<.+?>(.+?)</.+?>""".toRegex(), "$1")
        }
    }
}