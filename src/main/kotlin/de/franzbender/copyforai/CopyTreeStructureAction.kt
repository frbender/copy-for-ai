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
import java.awt.datatransfer.StringSelection

class CopyTreeStructureAction : AnAction() {

    companion object {
        private const val MAX_CHILDREN_PER_FOLDER = 100
    }

    override fun actionPerformed(event: AnActionEvent) {
        val selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (selectedFiles.isNullOrEmpty()) {
            showNotification("No files selected.", NotificationType.WARNING)
            return
        }

        val filesToProcess = mutableListOf<VirtualFile>()
        selectedFiles.forEach { collectFilesRecursively(it, filesToProcess) }

        if (filesToProcess.isEmpty()) {
            showNotification("No files to process.", NotificationType.WARNING)
            return
        }

        val commonPath = getCommonPath(filesToProcess)
        val rootName = commonPath.substringAfterLast('/', commonPath)
        val root = TreeNode(rootName)

        filesToProcess.forEach { file ->
            val relativePath = file.path.removePrefix(commonPath).trimStart('/')
            val segments = if (relativePath.isEmpty()) listOf(file.name) else relativePath.split('/')
            root.addPath(segments)
        }

        val treeStr = root.renderTree()
        val treeStrWithBackticks = "```\n$treeStr\n```"
        CopyPasteManager.getInstance().setContents(StringSelection(treeStrWithBackticks))
        showNotification("Tree structure copied to clipboard.", NotificationType.INFORMATION)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val selectedFiles = event.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        event.presentation.isEnabledAndVisible = selectedFiles?.isNotEmpty() ?: false
    }

    private fun collectFilesRecursively(file: VirtualFile, files: MutableList<VirtualFile>) {
        if (file.isDirectory) {
            file.children.forEach { collectFilesRecursively(it, files) }
        } else {
            files.add(file)
        }
    }

    private fun getCommonPath(files: List<VirtualFile>): String {
        val paths = files.map { it.path.split('/') }
        val minSize = paths.minOf { it.size }
        val commonSegments = mutableListOf<String>()
        for (i in 0 until minSize) {
            val segment = paths[0][i]
            if (paths.all { it[i] == segment }) commonSegments.add(segment) else break
        }
        return commonSegments.joinToString("/")
    }

    private fun showNotification(content: String, type: NotificationType) {
        Notifications.Bus.notify(Notification("Copy For AI", "Copy Tree Structure", content, type))
    }

    private class TreeNode(val name: String) {
        val children = sortedMapOf<String, TreeNode>()

        fun addPath(segments: List<String>) {
            if (segments.isEmpty()) return
            val head = segments.first()
            if (segments.size == 1) {
                children.putIfAbsent(head, TreeNode(head))
            } else {
                val child = children.getOrPut(head) { TreeNode(head) }
                child.addPath(segments.drop(1))
            }
        }

        fun renderTree(): String {
            val sb = StringBuilder()
            sb.append(name).append("\n")
            renderRecursive(sb, children.values.toList(), prefix = "    ") // Root's children should have indentation
            return sb.toString()
        }

        private fun renderRecursive(sb: StringBuilder, nodes: List<TreeNode>, prefix: String) {
            val total = nodes.size
            val limit = total.coerceAtMost(MAX_CHILDREN_PER_FOLDER)

            for (i in 0 until limit) {
                val node = nodes[i]
                val isLast = i == limit - 1

                // Proper indentation for all levels
                sb.append(prefix)
                sb.append(if (isLast) "└── " else "├── ")
                sb.append(node.name).append("\n")

                val newPrefix = prefix + if (isLast) "    " else "│   "

                // Recursively print children with correct indentation
                renderRecursive(sb, node.children.values.toList(), newPrefix)
            }

            if (total > MAX_CHILDREN_PER_FOLDER) {
                sb.append(prefix)
                sb.append("└── ... (truncated, showing first $MAX_CHILDREN_PER_FOLDER)\n")
            }
        }
    }
}
