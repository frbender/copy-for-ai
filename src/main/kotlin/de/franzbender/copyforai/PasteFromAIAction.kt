// src/main/kotlin/de/franzbender/copyforai/PasteFromAIAction.kt
package de.franzbender.copyforai

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.InvalidPathException
import java.nio.file.Paths

class PasteFromAIAction : AnAction() {

    private val log = Logger.getInstance(PasteFromAIAction::class.java)
    private val codeBlockRegex = Regex("""<code\s+to="([^"]+)"\s*>(.*?)</code>""", RegexOption.DOT_MATCHES_ALL)
    // Regex to find ``` optionally followed by a language and newline, capturing the content, ending with ```
    private val backticksRegex = Regex("""^```(?:[a-zA-Z0-9]*)?\n?(.*?)\n?```$""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))


    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return // Need project context

        val clipboardText = getClipboardContent()
        if (clipboardText == null) {
            showNotification(project, "Clipboard does not contain text.", NotificationType.WARNING)
            return
        }

        val parsedBlocks = parseCodeBlocks(clipboardText)
        if (parsedBlocks.isEmpty()) {
            showNotification(project, "No code blocks found in the expected format (<code to=\"...\">...</code>).", NotificationType.INFORMATION)
            return
        }

        val validationResult = validateCodeBlocks(parsedBlocks)
        val validBlocks = validationResult.first
        val errors = validationResult.second

        if (errors.isNotEmpty()) {
            val errorMsg = "Found ${parsedBlocks.size} code blocks, but some have issues:\n" + errors.joinToString("\n") + "\n\nProceed with the valid ones?"
            val choice = Messages.showOkCancelDialog(
                project,
                errorMsg,
                "Code Block Validation Issues",
                "Proceed (${validBlocks.size} Valid)",
                "Cancel",
                Messages.getWarningIcon()
            )
            if (choice != Messages.OK) {
                showNotification(project, "Operation cancelled by user.", NotificationType.INFORMATION)
                return
            }
        }

        if (validBlocks.isEmpty()) {
            showNotification(project, "No valid code blocks found to apply.", NotificationType.WARNING)
            return
        }

        if (!showConfirmationDialog(project, validBlocks)) {
            showNotification(project, "Operation cancelled by user.", NotificationType.INFORMATION)
            return
        }

        applyCodeBlocks(project, validBlocks)
    }

    private fun getClipboardContent(): String? {
        return CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor)
    }

    private fun parseCodeBlocks(text: String): List<CodeBlock> {
        return codeBlockRegex.findAll(text).mapNotNull { matchResult ->
            val path = matchResult.groupValues.getOrNull(1)?.trim()
            var content = matchResult.groupValues.getOrNull(2)?.trim()

            if (path.isNullOrEmpty() || content == null) {
                log.warn("Skipping block with missing path or content. Path: '$path'")
                return@mapNotNull null // Skip if path is missing or content is null
            }

            // Remove ``` code fences if present
            val backticksMatch = backticksRegex.find(content)
            if (backticksMatch != null) {
                content = backticksMatch.groupValues.getOrNull(1)?.trim() ?: content
            }

            CodeBlock(path, content)
        }.toList()
    }

    private fun validateCodeBlocks(blocks: List<CodeBlock>): Pair<List<CodeBlock>, List<String>> {
        val validBlocks = mutableListOf<CodeBlock>()
        val errors = mutableListOf<String>()

        blocks.forEachIndexed { index, block ->
            var isValid = true
            val errorDetails = mutableListOf<String>()

            // 1. Check if path looks absolute (basic check)
            try {
                val file = File(block.path)
                if (!file.isAbsolute) {
                    errorDetails.add("Path is not absolute")
                    isValid = false
                }
                // Check for invalid characters (optional, basic example)
                Paths.get(block.path)

            } catch (e: InvalidPathException) {
                errorDetails.add("Path contains invalid characters or format: ${e.message}")
                isValid = false
            } catch (e: Exception) {
                errorDetails.add("Unexpected error validating path: ${e.message}")
                isValid = false
            }

            // Add more checks if needed (e.g., path length, disallowed components)

            if (isValid) {
                validBlocks.add(block)
            } else {
                errors.add("Block ${index + 1} ('${block.path.take(50)}...'): ${errorDetails.joinToString(", ")}")
            }
        }
        return Pair(validBlocks, errors)
    }


    private fun showConfirmationDialog(project: Project, blocks: List<CodeBlock>): Boolean {
        val fileList = blocks.map {
            val file = File(it.path)
            val action = if (file.exists()) "[REPLACE]" else "[CREATE]"
            "$action ${it.path}"
        }.joinToString("\n")

        // Limit the number of files displayed in the dialog to avoid huge messages
        val maxFilesToShow = 20
        val displayList = blocks.take(maxFilesToShow).joinToString("\n") {
            val file = File(it.path)
            val action = if (file.exists()) "[REPLACE]" else "[CREATE]"
            "$action ${it.path}"
        }
        val truncatedMessage = if (blocks.size > maxFilesToShow) "\n... (and ${blocks.size - maxFilesToShow} more)" else ""


        val message = "You are about to apply ${blocks.size} code block(s):\n\n$displayList$truncatedMessage\n\nAre you sure you want to proceed?"
        val title = "Confirm Paste from AI"
        return Messages.showOkCancelDialog(project, message, title, "Apply Changes", "Cancel", Messages.getQuestionIcon()) == Messages.OK
    }

    private fun applyCodeBlocks(project: Project, blocks: List<CodeBlock>) {
        // *** CORRECTED WriteCommandAction call ***
        WriteCommandAction.runWriteCommandAction(project, "Paste Code from AI", null, {
            var createdCount = 0
            var replacedCount = 0
            val failedPaths = mutableListOf<String>()

            blocks.forEach { block ->
                try {
                    val file = File(block.path)
                    val parentDir = file.parentFile

                    // Ensure parent directories exist
                    if (parentDir != null && !parentDir.exists()) {
                        if (!parentDir.mkdirs()) {
                            throw Exception("Failed to create parent directories for: ${block.path}")
                        }
                        // Refresh VFS for the created parent directory
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parentDir)
                            ?: log.warn("Could not find virtual file for created parent directory: ${parentDir.path}")
                    } else if (parentDir == null) {
                        throw Exception("Cannot determine parent directory for root path: ${block.path}")
                    }


                    // Find or create the VirtualFile
                    val parentVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(parentDir)
                        ?: throw Exception("Could not get VirtualFile for parent directory: ${parentDir.path}")

                    val existingVirtualFile = parentVirtualFile.findChild(file.name)
                    val targetVirtualFile: VirtualFile

                    if (existingVirtualFile == null || !existingVirtualFile.isValid) {
                        targetVirtualFile = parentVirtualFile.createChildData(this, file.name)
                        createdCount++
                        log.info("Creating file: ${block.path}")
                    } else {
                        targetVirtualFile = existingVirtualFile
                        replacedCount++
                        log.info("Replacing file: ${block.path}")
                    }

                    // Write content to the file
                    targetVirtualFile.setBinaryContent(block.content.toByteArray(StandardCharsets.UTF_8))
                    log.info("Successfully wrote content to ${block.path}")

                } catch (e: Exception) {
                    log.error("Failed to apply code block to ${block.path}", e)
                    failedPaths.add("${block.path} (${e.message})")
                }
            }

            // Refresh the affected files/directories at the end
            val filesToRefresh = blocks.mapNotNull { LocalFileSystem.getInstance().findFileByIoFile(File(it.path)) }
            VfsUtil.markDirtyAndRefresh(true, true, true, *filesToRefresh.toTypedArray())


            // Final Notification
            val summary = mutableListOf<String>()
            if (createdCount > 0) summary.add("Created $createdCount file(s)")
            if (replacedCount > 0) summary.add("Replaced $replacedCount file(s)")

            if (failedPaths.isEmpty()) {
                showNotification(project, "${summary.joinToString(", ")} successfully.", NotificationType.INFORMATION)
            } else {
                val errorList = failedPaths.joinToString("\n - ")
                showNotification(project, "${summary.joinToString(", ")} with errors:\nFailed to apply:\n - $errorList", NotificationType.ERROR)
            }

        }) // No vararg PsiFile argument here
    }


    override fun update(event: AnActionEvent) {
        // Enable if there's text in the clipboard potentially
        // *** CORRECTED method name ***
        val hasText = CopyPasteManager.getInstance().areDataFlavorsAvailable(DataFlavor.stringFlavor)
        event.presentation.isEnabledAndVisible = hasText && event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun showNotification(project: Project, content: String, type: NotificationType) {
        ApplicationManager.getApplication().invokeLater {
            Notifications.Bus.notify(Notification("Copy For AI", "Paste from AI", content, type), project)
        }
    }
}