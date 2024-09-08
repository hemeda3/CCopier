package com.mapledoum.cCopier;

import com.intellij.ide.IdeView;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;
import org.yaml.snakeyaml.Yaml;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

public class CopyFolderContentAction extends AnAction {
    private static final String DEFAULT_CONFIG_FILE = "default-config.yaml";

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.EDT;
    }


    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null) return;

        IdeView view = e.getData(LangDataKeys.IDE_VIEW);
        if (view == null) return;

        PsiDirectory[] directories = view.getDirectories();
        if (directories.length == 0) return;

        PsiDirectory directory = directories[0];

        Map<String, Object> config = loadConfiguration(project);


        copyFolderContent(project, directory, config);
    }


    private Map<String, Object> loadConfiguration(Project project) {
        Map<String, Object> config = loadDefaultConfiguration();

        VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
        if (projectDir != null) {
            Path configPath = Paths.get(projectDir.getPath(), "cCopier.yaml");
            if (Files.exists(configPath)) {
                try (InputStream inputStream = Files.newInputStream(configPath)) {
                    Yaml yaml = new Yaml();
                    Object loadedConfig = yaml.load(inputStream);
                    if (loadedConfig instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> projectConfig = (Map<String, Object>) loadedConfig;
                        config.putAll(projectConfig);  // Override default with project-specific config
                    } else {
                        throw new IOException("Invalid YAML format in cCopier.yaml");
                    }
                } catch (IOException ex) {
                    Notification notification = CopyFolderContentNotificationGroup.getInstance()
                            .createNotification("Error reading cCopier.yaml: " + ex.getMessage(), NotificationType.ERROR);
                    notification.notify(project);
                }
            }
        }

        return config;
    }
    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        e.getPresentation().setEnabledAndVisible(project != null &&
                e.getData(LangDataKeys.IDE_VIEW) != null &&
                Objects.requireNonNull(e.getData(LangDataKeys.IDE_VIEW)).getDirectories().length > 0);
    }
    private Map<String, Object> loadDefaultConfiguration() {
        try (InputStream inputStream = getClass().getResourceAsStream("/" + DEFAULT_CONFIG_FILE)) {
            if (inputStream == null) {
                // Log that the default config file couldn't be found
                return new HashMap<>();  // Return empty map if default config can't be found
            }
            Yaml yaml = new Yaml();
            Object loadedConfig = yaml.load(inputStream);
            if (loadedConfig instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) loadedConfig;
                return config;
            } else {
                // Log that the default config file is not in the expected format
                return new HashMap<>();
            }
        } catch (IOException e) {
            // Log the exception
            return new HashMap<>();  // Return empty map if default config can't be loaded
        }
    }
    private void copyFolderContent(Project project, PsiDirectory directory, Map<String, Object> config) {
        StringBuilder content = new StringBuilder();
        List<String> copiedFiles = new ArrayList<>();

        String topInstruction = (String) config.getOrDefault("top_instruction", "");
        String commentPrefix = (String) config.getOrDefault("comment_prefix", "//");

        if (!topInstruction.isEmpty()) {
            content.append(commentPrefix).append(" ").append(topInstruction).append("\n\n");
        }

        collectFolderContent(project, directory, content, copiedFiles, config);

        // Remove last separator if not wanted
        boolean includeLastSeparator = (boolean) config.getOrDefault("include_last_separator", false);
        String fileSeparator = (String) config.getOrDefault("file_separator", "\n\n");
        if (!includeLastSeparator && content.length() >= fileSeparator.length()) {
            content.setLength(content.length() - fileSeparator.length());
        }

        if (!copiedFiles.isEmpty()) {
            StringSelection stringSelection = new StringSelection(content.toString());
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(stringSelection, null);

            showCopiedFilesNotification(project, copiedFiles);
        } else {
            showNoFilesNotification(project);
        }
    }
    private void collectFolderContent(Project project, PsiDirectory directory, StringBuilder content,
                                      List<String> copiedFiles, Map<String, Object> config) {
        String fileInstructions = (String) config.getOrDefault("to_file_instructions", "");
        String commentPrefix = (String) config.getOrDefault("comment_prefix", "//");
        String filePrefix = (String) config.getOrDefault("file_prefix", "");
        String fileSeparator = (String) config.getOrDefault("file_separator", "\n\n");

        for (PsiFile file : directory.getFiles()) {
            String relativePath = getRelativePath(project, file.getVirtualFile(), config);
            content.append(commentPrefix).append(" ");
            if (!filePrefix.isEmpty()) {
                content.append(filePrefix).append(" ");
            }
            content.append(relativePath).append("\n");

            if (!fileInstructions.isEmpty()) {
                content.append(commentPrefix).append(" ").append(fileInstructions).append("\n");
            }
            content.append(file.getText()).append(fileSeparator);
            copiedFiles.add(relativePath);
        }

        for (PsiDirectory subDir : directory.getSubdirectories()) {
            collectFolderContent(project, subDir, content, copiedFiles, config);
        }
    }

    private String getRelativePath(Project project, VirtualFile file, Map<String, Object> config) {
        boolean useRelativePaths = (boolean) config.getOrDefault("use_relative_paths", true);

        if (useRelativePaths) {
            VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
            if (projectDir != null) {
                String projectPath = projectDir.getPath();
                String filePath = file.getPath();
                if (filePath.startsWith(projectPath)) {
                    return filePath.substring(projectPath.length() + 1);
                }
            }
            return file.getName(); // Fallback to just the file name if we can't determine the relative path
        } else {
            return file.getPath(); // Return full path if use_relative_paths is false
        }
    }

    private void showCopiedFilesNotification(Project project, List<String> copiedFiles) {
        String message = String.format("Copied content of %d files", copiedFiles.size());
        Notification notification = CopyFolderContentNotificationGroup.getInstance()
                .createNotification(message, NotificationType.INFORMATION);
        notification.notify(project);
    }

    private void showNoFilesNotification(Project project) {
        Notification notification = CopyFolderContentNotificationGroup.getInstance()
                .createNotification("No files to copy in the selected folder", NotificationType.INFORMATION);
        notification.notify(project);
    }


}