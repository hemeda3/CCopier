package org.intellij.sdk.toolWindow;

import com.intellij.icons.AllIcons;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.util.treeView.PresentableNodeDescriptor;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.keymap.KeymapManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FileListToolWindowFactory implements ToolWindowFactory, DumbAware {

    private static volatile Logger LOG;
    private static volatile boolean loggingEnabled;
    private static Logger getLogger() {
        if (LOG == null) {
            LOG = Logger.getInstance(FileListToolWindowFactory.class);
        }
        return LOG;
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        JPanel waitPanel = new JPanel(new BorderLayout());
        JLabel waitLabel = new JLabel("Waiting for indexing to finish...", SwingConstants.CENTER);
        waitPanel.add(waitLabel, BorderLayout.CENTER);
        Content waitContent = ContentFactory.getInstance().createContent(waitPanel, "", false);
        toolWindow.getContentManager().addContent(waitContent);

        DumbService.getInstance(project).runWhenSmart(() -> {
            ApplicationManager.getApplication().invokeLater(() -> {
                loggingEnabled = true;
                FileListToolWindowContent toolWindowContent = new FileListToolWindowContent(project, toolWindow);
                Content content = ContentFactory.getInstance().createContent(toolWindowContent.getContentPanel(), "", false);
                toolWindow.getContentManager().removeAllContents(true);
                toolWindow.getContentManager().addContent(content);
            });
        });
    }

    private static class FileListToolWindowContent {
        private final JPanel contentPanel = new JPanel(new BorderLayout());
        private final Tree fileTree;
        private final DefaultTreeModel treeModel;
        private final Project project;

        public FileListToolWindowContent(Project project, ToolWindow toolWindow) {
            this.project = project;
            DefaultMutableTreeNode root = new DefaultMutableTreeNode("Project");
            treeModel = new DefaultTreeModel(root);
            fileTree = new Tree(treeModel);
            fileTree.setCellRenderer(new FileTreeCellRenderer());

            Color background = UIManager.getColor("Tree.background");
            if (background != null) {
                fileTree.setBackground(background);
            }

            JScrollPane scrollPane = new JScrollPane(fileTree);

            if (background != null) {
                scrollPane.setBackground(background);
                scrollPane.getViewport().setBackground(background);
            }

            contentPanel.add(scrollPane, BorderLayout.CENTER);

            initializeToolWindowContent();
        }

        private void initializeToolWindowContent() {
            ApplicationManager.getApplication().invokeLater(() -> {
                JComponent toolbar = createToolbar();
                contentPanel.add(toolbar, BorderLayout.NORTH);

                listProjectSourceFiles();

                AnAction copyAction = createCopyAction();
                registerCopyShortcut(copyAction);
            });
        }

        private AnAction createCopyAction() {
            return new AnAction("Copy Selected Folder Content", "Copy all files' content from the selected folder", AllIcons.Actions.Copy) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    copySelectedFolderContent();
                }

                @Override
                public void update(@NotNull AnActionEvent e) {
                    e.getPresentation().setEnabled(fileTree.getSelectionPath() != null);
                }
            };
        }

        private JComponent createToolbar() {
            DefaultActionGroup group = new DefaultActionGroup();

            AnAction copyAction = createCopyAction();
            group.add(copyAction);

            group.add(new AnAction("Refresh", "Refresh file tree", AllIcons.Actions.Refresh) {
                @Override
                public void actionPerformed(@NotNull AnActionEvent e) {
                    refreshFileTree();
                }
            });

            group.add(new ToggleAction("Enable Logging", "Enable/Disable logging", AllIcons.General.Settings) {
                @Override
                public boolean isSelected(@NotNull AnActionEvent e) {
                    return loggingEnabled;
                }

                @Override
                public void setSelected(@NotNull AnActionEvent e, boolean state) {
                    loggingEnabled = state;
                    log("Logging " + (state ? "enabled" : "disabled"));
                }
            });

            ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("FileListToolbar", group, true);
            toolbar.setTargetComponent(fileTree);
            return toolbar.getComponent();
        }

        private void registerCopyShortcut(AnAction copyAction) {
            ShortcutSet shortcutSet = new CustomShortcutSet(
                    KeymapManager.getInstance().getActiveKeymap().getShortcuts("$Copy")
            );
            copyAction.registerCustomShortcutSet(shortcutSet, fileTree);
        }

        private void refreshFileTree() {
            ApplicationManager.getApplication().invokeLater(this::listProjectSourceFiles);
        }

        private void copySelectedFolderContent() {
            TreePath selectedPath = fileTree.getSelectionPath();
            if (selectedPath != null) {
                DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) selectedPath.getLastPathComponent();
                StringBuilder allContent = new StringBuilder();
                List<String> copiedFiles = new ArrayList<>();
                copyFolderContent(selectedNode, allContent, copiedFiles);
                if (!copiedFiles.isEmpty()) {
                    copyContentToClipboard(allContent.toString());
                    showCopiedFilesNotification(copiedFiles);
                } else {
                    showNoFilesNotification();
                }
            }
        }

        private void showNoFilesNotification() {
            NotificationGroup notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("File List Tool Window");
            Notification notification = notificationGroup.createNotification("No files to copy in the selected folder", NotificationType.INFORMATION);
            notification.notify(project);
        }

        private void copyFolderContent(DefaultMutableTreeNode node, StringBuilder content, List<String> copiedFiles) {
            Object userObject = node.getUserObject();
            if (userObject instanceof FileNodeDescriptor) {
                FileNodeDescriptor descriptor = (FileNodeDescriptor) userObject;
                VirtualFile file = descriptor.getElement();
                if (!file.isDirectory()) {
                    String relativePath = getRelativePath(file);
                    content.append("//FileName: ").append(relativePath).append("\n");
                    if (isMediaFile(file)) {
                        content.append("[Media file content not included]\n\n");
                    } else {
                        content.append(readFileContent(file)).append("\n\n");
                    }
                    copiedFiles.add(relativePath);
                }
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                copyFolderContent((DefaultMutableTreeNode) node.getChildAt(i), content, copiedFiles);
            }
        }

        private String getRelativePath(VirtualFile file) {
            VirtualFile projectRoot = project.getBasePath() != null ? project.getBaseDir() : null;
            if (projectRoot != null) {
                String filePath = file.getPath();
                String rootPath = projectRoot.getPath();
                if (filePath.startsWith(rootPath)) {
                    return filePath.substring(rootPath.length() + 1);
                }
            }
            return file.getName();
        }

        private void copyContentToClipboard(String content) {
            if (!content.isEmpty()) {
                Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(new StringSelection(content), null);
                log("Content copied to clipboard. Length: " + content.length());
            } else {
                log("No content to copy.");
            }
        }

        private void showCopiedFilesNotification(List<String> copiedFiles) {
            String message = String.format("Copied %d files:\n%s", copiedFiles.size(), String.join("\n", copiedFiles));
            NotificationGroup notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("File List Tool Window");
            Notification notification = notificationGroup.createNotification(message, NotificationType.INFORMATION);
            notification.notify(project);
        }

        private String readFileContent(VirtualFile file) {
            try {
                return new String(file.contentsToByteArray(), file.getCharset());
            } catch (IOException e) {
                log("Error reading file: " + e.getMessage(), e);
                return "Error reading file: " + e.getMessage();
            }
        }

        private void listProjectSourceFiles() {
            ApplicationManager.getApplication().runReadAction(() -> {
                DefaultMutableTreeNode root = (DefaultMutableTreeNode) treeModel.getRoot();
                root.removeAllChildren();
                Module[] modules = ModuleManager.getInstance(project).getModules();
                for (Module module : modules) {
                    DefaultMutableTreeNode moduleNode = new DefaultMutableTreeNode(new ModuleNodeDescriptor(module));
                    root.add(moduleNode);
                    DefaultMutableTreeNode mediaNode = new DefaultMutableTreeNode(new CustomNodeDescriptor("Media Files", AllIcons.Nodes.PpLib));

                    moduleNode.add(mediaNode);
                    for (VirtualFile sourceRoot : ModuleRootManager.getInstance(module).getSourceRoots(false)) {
                        addFilesRecursively(sourceRoot, moduleNode, mediaNode);
                    }
                    if (mediaNode.getChildCount() == 0) {
                        moduleNode.remove(mediaNode);
                    }
                }
                treeModel.reload();
                TreeUtil.expandAll(fileTree);
            });
        }

        private void addFilesRecursively(VirtualFile dir, DefaultMutableTreeNode parentNode, DefaultMutableTreeNode mediaNode) {
            log("Adding files from directory: " + dir.getName());
            FileNodeDescriptor dirDescriptor = new FileNodeDescriptor(dir);
            DefaultMutableTreeNode dirNode = new DefaultMutableTreeNode(dirDescriptor);
            parentNode.add(dirNode);

            for (VirtualFile file : dir.getChildren()) {
                if (file.isDirectory()) {
                    addFilesRecursively(file, dirNode, mediaNode);
                } else {
                    log("Adding file: " + file.getName());
                    FileNodeDescriptor fileDescriptor = new FileNodeDescriptor(file);
                    DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(fileDescriptor);
                    if (isMediaFile(file)) {
                        mediaNode.add(fileNode);
                    } else {
                        dirNode.add(fileNode);
                    }
                }
            }

            if (dirNode.getChildCount() == 0) {
                parentNode.remove(dirNode);
            }
        }

        private boolean isMediaFile(VirtualFile file) {
            String fileTypeName = FileTypeManager.getInstance().getFileTypeByFile(file).getName().toLowerCase();
            return fileTypeName.contains("image") || fileTypeName.contains("video") || fileTypeName.contains("audio");
        }

        public JPanel getContentPanel() {
            return contentPanel;
        }
    }

    private static class ModuleNodeDescriptor extends PresentableNodeDescriptor<Module> {
        private final Module module;

        ModuleNodeDescriptor(Module module) {
            super(module.getProject(), null);
            this.module = module;
        }

        @Override
        protected void update(@NotNull PresentationData presentation) {
            presentation.setPresentableText(module.getName());
            presentation.setIcon(AllIcons.Nodes.Module);
        }

        @Override
        public Module getElement() {
            return module;
        }
    }

    private static class CustomNodeDescriptor extends PresentableNodeDescriptor<String> {
        private final String text;
        private final Icon icon;

        CustomNodeDescriptor(String text, Icon icon) {
            super(null, null);
            this.text = text;
            this.icon = icon;
        }

        @Override
        protected void update(@NotNull PresentationData presentation) {
            presentation.setPresentableText(text);
            presentation.setIcon(icon);
        }

        @Override
        public String getElement() {
            return text;
        }
    }

    private static class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            log("Rendering node: " + value);

            if (value instanceof DefaultMutableTreeNode) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
                Object userObject = node.getUserObject();

                log("User object: " + userObject);

                if (userObject instanceof FileNodeDescriptor) {
                    FileNodeDescriptor descriptor = (FileNodeDescriptor) userObject;
                    VirtualFile file = descriptor.getElement();
                    setIcon(file.isDirectory() ? AllIcons.Nodes.Folder : file.getFileType().getIcon());
                    String text = file.getName();
                    setText(text);
                    log("Setting text for FileNodeDescriptor: " + text);
                } else if (userObject instanceof ModuleNodeDescriptor) {
                    ModuleNodeDescriptor descriptor = (ModuleNodeDescriptor) userObject;
                    setIcon(AllIcons.Nodes.Module);
                    String text = descriptor.getElement().getName();
                    setText(text);
                    log("Setting text for ModuleNodeDescriptor: " + text);
                } else if (userObject instanceof CustomNodeDescriptor) {
                    CustomNodeDescriptor descriptor = (CustomNodeDescriptor) userObject;
                    setIcon(descriptor.icon);
                    String text = descriptor.getElement();
                    setText(text);
                    log("Setting text for CustomNodeDescriptor: " + text);
                } else if (userObject instanceof String) {
                    setText((String) userObject);
                    log("Setting text for String: " + userObject);
                }
            }

            log("Final text set: " + getText());
            return this;
        }
    }

    private static class FileNodeDescriptor extends PresentableNodeDescriptor<VirtualFile> {
        private final VirtualFile file;

        FileNodeDescriptor(VirtualFile file) {
            super(null, null);
            this.file = file;
            log("Created FileNodeDescriptor for: " + file.getName());
        }

        @Override
        protected void update(@NotNull PresentationData presentation) {
            String name = file.getName();
            presentation.setPresentableText(name);
            presentation.setIcon(file.isDirectory() ? AllIcons.Nodes.Folder : file.getFileType().getIcon());
            log("Updated PresentationData for: " + name);
        }

        @Override
        public VirtualFile getElement() {
            return file;
        }
    }

    private static void log(String message) {
        if (loggingEnabled) {
            getLogger().info(message);
        }
    }

    private static void log(String message, Throwable t) {
        if (loggingEnabled) {
            getLogger().info(message, t);
        }
    }
}