package de.franzbender.copyforai

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.VirtualFile
import de.franzbender.copyforai.helpers.CompactorRegistry
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets

class CopyForAICompactedAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (selectedFiles.isNullOrEmpty()) {
            showNotification("No files selected.", NotificationType.WARNING)
            return
        }
        val filesToProcess = mutableListOf<VirtualFile>()
        selectedFiles.forEach { collectFilesRecursively(it, filesToProcess) }
        if (filesToProcess.size > 1000) {
            showNotification("Too many files selected: ${filesToProcess.size}. Maximum allowed is 1000.", NotificationType.ERROR)
            return
        }
        val output = StringBuilder()
        for (file in filesToProcess) {
            if (file.isDirectory) continue
            val filePath = file.path
            when {
                file.extension == "ipynb" -> {
                    val ipynbContent = file.loadText()
                    if (ipynbContent != null) {
                        var compacted = IpynbExtractor.extractCells(ipynbContent)
                        val compactor = CompactorRegistry.getCompactor(file.name, file.extension!!, compacted)
                        if (compactor != null) {
                            compacted = compactor.compact(compacted)
                        }
                        output.append("# file: $filePath (ipynb)\n")
                        output.append("```\n")
                        output.append(compacted)
                        output.append("\n```\n\n")
                    } else {
                        showNotification("Error reading .ipynb file: $filePath", NotificationType.ERROR)
                        return
                    }
                }
                file.isBinary() -> {
                    output.append("# file: $filePath (binary)\n")
                    output.append("Length: ${file.length} bytes\n\n")
                }
                else -> {
                    val content = file.loadText() ?: "Error reading file content"
                    val compactor = CompactorRegistry.getCompactor(file.name, file.extension ?: "", content)
                    val compactedContent = compactor?.compact(content) ?: content
                    val truncatedContent = if (compactedContent.length > 10000)
                        "${compactedContent.take(10000)}\n... (truncated)" else compactedContent
                    output.append("# file: $filePath (text)\n")
                    output.append("```\n")
                    output.append(truncatedContent)
                    output.append("\n```\n\n")
                }
            }
        }
        CopyPasteManager.getInstance().setContents(StringSelection(output.toString()))
        showNotification("Copied ${filesToProcess.size} files to clipboard.", NotificationType.INFORMATION)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabledAndVisible = selectedFiles?.isNotEmpty() ?: false
    }

    private fun collectFilesRecursively(file: VirtualFile, files: MutableList<VirtualFile>) {
        if (file.isDirectory) file.children.forEach { collectFilesRecursively(it, files) }
        else files.add(file)
    }

    private fun VirtualFile.isBinary(): Boolean {
        return try {
            val content = contentsToByteArray()
            val text = String(content, StandardCharsets.UTF_8)
            text.any { it.code < 32 && it !in "\n\r\t" }
        } catch (e: Exception) {
            true
        }
    }

    private fun VirtualFile.loadText(): String? {
        return try {
            String(contentsToByteArray(), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun showNotification(content: String, type: NotificationType) {
        Notifications.Bus.notify(Notification("Copy For AI", "Copy For AI Compacted", content, type))
    }
}
