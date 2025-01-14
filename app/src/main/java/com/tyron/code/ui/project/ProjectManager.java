package com.tyron.code.ui.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.base.Charsets;
import com.google.common.base.Throwables;
import com.tyron.builder.BuildModule;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.model.AndroidArtifact;
import com.tyron.builder.model.Dependencies;
import com.tyron.builder.model.Variant;
import com.tyron.builder.model.v2.ide.AndroidLibraryData;
import com.tyron.builder.model.v2.ide.ArtifactDependencies;
import com.tyron.builder.model.v2.ide.GraphItem;
import com.tyron.builder.model.v2.ide.Library;
import com.tyron.builder.model.v2.ide.LibraryType;
import com.tyron.builder.model.v2.ide.SourceProvider;
import com.tyron.builder.model.v2.ide.SourceSetContainer;
import com.tyron.builder.model.v2.models.AndroidProject;
import com.tyron.builder.model.v2.models.BasicAndroidProject;
import com.tyron.builder.model.v2.models.VariantDependencies;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidContentRoot;
import com.tyron.builder.project.api.ContentRoot;
import com.tyron.builder.project.api.JavaModule;
import com.tyron.builder.project.api.Module;
import com.tyron.builder.project.impl.AndroidModuleImpl;
import com.tyron.code.gradle.util.GradleLaunchUtil;
import com.tyron.code.template.CodeTemplate;
import com.tyron.code.ui.editor.log.AppLogFragment;
import com.tyron.code.util.ProjectUtils;
import com.tyron.completion.java.compiler.Parser;
import com.tyron.completion.java.parse.CompilationInfo;
import com.tyron.completion.java.provider.CompletionEngine;
import com.tyron.completion.java.provider.PruneMethodBodies;
import com.tyron.completion.progress.ProgressManager;

import org.apache.commons.io.FileUtils;
import org.gradle.api.GradleException;
import org.gradle.tooling.BuildActionExecuter;
import org.gradle.tooling.BuildException;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ModelBuilder;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.events.ProgressListener;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.ExternalDependency;
import org.gradle.tooling.model.HierarchicalElement;
import org.gradle.tooling.model.idea.IdeaContentRoot;
import org.gradle.tooling.model.idea.IdeaDependency;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaModuleDependency;
import org.gradle.tooling.model.idea.IdeaProject;
import org.gradle.tooling.model.idea.IdeaSourceDirectory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;

public class ProjectManager {

    public interface TaskListener {
        void onTaskStarted(String message);

        void onComplete(Project project, boolean success, String message);
    }

    public interface OnProjectOpenListener {
        void onProjectOpen(Project project);
    }

    private static volatile ProjectManager INSTANCE = null;

    public static synchronized ProjectManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new ProjectManager();
        }
        return INSTANCE;
    }

    private final List<OnProjectOpenListener> mProjectOpenListeners = new ArrayList<>();
    private volatile Project mCurrentProject;

    private ProjectManager() {

    }

    public void addOnProjectOpenListener(OnProjectOpenListener listener) {
        if (!CompletionEngine.isIndexing() && mCurrentProject != null) {
            listener.onProjectOpen(mCurrentProject);
        }
        mProjectOpenListeners.add(listener);
    }

    public void removeOnProjectOpenListener(OnProjectOpenListener listener) {
        mProjectOpenListeners.remove(listener);
    }

    public void openProject(Project project,
                            boolean downloadLibs,
                            TaskListener listener,
                            ILogger logger) {
        ProgressManager.getInstance()
                .runNonCancelableAsync(() -> doOpenProject(project,
                        downloadLibs,
                        listener,
                        logger));
    }

    private void doOpenProject(Project project,
                               boolean downloadLibs,
                               TaskListener mListener,
                               ILogger logger) {
        mCurrentProject = project;

        boolean shouldReturn = false;

        try {
            mCurrentProject.open();
        } catch (IOException exception) {
            logger.warning("Failed to open project: " + exception.getMessage());
            shouldReturn = true;
        }

        if (shouldReturn) {
            mListener.onComplete(project, false, "Failed to open project.");
            return;
        }

        try {
            mCurrentProject.setIndexing(true);
            mCurrentProject.index();
        } catch (IOException exception) {
            logger.warning("Failed to open project: " + exception.getMessage());
        }

        // the following will extract the jar files if it does not exist
        BuildModule.getAndroidJar();
        BuildModule.getLambdaStubs();

        GradleConnector gradleConnector = GradleConnector.newConnector();
        gradleConnector.forProjectDirectory(mCurrentProject.getRootFile());
        gradleConnector.useDistribution(URI.create("codeAssist"));

        try (ProjectConnection projectConnection = gradleConnector.connect()) {
            mListener.onTaskStarted("Build model");

            // clears the logs
            AppLogFragment.outputStream.write("\033[H\033[2J".getBytes());

            ProgressListener progressListener =
                    event -> mListener.onTaskStarted(event.getDisplayName());

            BuildActionExecuter<ModelContainerV2> executer =
                    projectConnection.action(new GetAndroidModelV2Action("debug"));
            executer.addProgressListener(progressListener);
            executer.setColorOutput(false);
            executer.setStandardError(AppLogFragment.outputStream);
            executer.setStandardOutput(AppLogFragment.outputStream);
            GradleLaunchUtil.configureLauncher(executer);

            ModelContainerV2 modelContainer = executer.run();
            ModelContainerV2.ModelInfo appProject = modelContainer.getProject(":app", ":");

            // remove the previous models
            mCurrentProject.clear();
            buildModel(appProject, project);
        } catch (Throwable t) {
            Throwable throwable = t;
            if (throwable instanceof BuildException) {
                BuildException buildException = (BuildException) throwable;
                if (buildException.getCause() != null) {
                    throwable = buildException.getCause();
                }
            }
            mListener.onComplete(mCurrentProject,
                    false,
                    Throwables.getStackTraceAsString(throwable) + "\n");
            return;
        }

        mProjectOpenListeners.forEach(it -> it.onProjectOpen(mCurrentProject));

        mCurrentProject.setIndexing(false);
        mListener.onComplete(project, true, "Index successful");
    }

    private void buildModel(ModelContainerV2.ModelInfo modelInfo, Project currentProject) throws IOException {
        AndroidModuleImpl impl = new AndroidModuleImpl(modelInfo.getProjectDir());

        // de-structure model info fields
        AndroidProject androidProject = modelInfo.getAndroidProject();
        BasicAndroidProject basicAndroidProject = modelInfo.getBasicAndroidProject();
        VariantDependencies variantDependencies = modelInfo.getVariantDependencies();

        assert androidProject != null;
        assert basicAndroidProject != null;
        assert variantDependencies != null;

        // basic info
        impl.setNamespace(androidProject.getNamespace());

        // add main source set
        SourceSetContainer mainSourceSet = basicAndroidProject.getMainSourceSet();
        if (mainSourceSet != null) {
            SourceProvider sourceProvider = mainSourceSet.getSourceProvider();

            File contentRootDirectory = new File(impl.getRootFile(), "src/" + sourceProvider.getName());
            AndroidContentRoot contentRoot = new AndroidContentRoot(contentRootDirectory);
            contentRoot.setJavaDirectories(sourceProvider.getJavaDirectories());
            impl.addContentRoot(contentRoot);
        }

        Map<String, Library> libraries = variantDependencies.getLibraries();
        ArtifactDependencies mainArtifact = variantDependencies.getMainArtifact();
        List<GraphItem> compileDependencies = mainArtifact.getCompileDependencies();


        Deque<GraphItem> queue = new LinkedList<>(compileDependencies);
        Set<GraphItem> visitedDependencies = new HashSet<>();

        while (!queue.isEmpty()) {
            GraphItem compileDependency = queue.removeFirst();

            Library library = libraries.get(compileDependency.getKey());
            if (library == null) {
                continue;
            }

            File artifact = library.getArtifact();

            LibraryType type = library.getType();
            switch (type) {
                case PROJECT:
                    // TODO: support project dependencies
                    break;
                case JAVA_LIBRARY:
                    if (artifact != null && artifact.exists()) {
                        impl.addLibrary(artifact);
                    }
                    break;
                case ANDROID_LIBRARY:
                    AndroidLibraryData androidLibraryData = library.getAndroidLibraryData();
                    assert androidLibraryData != null;
                    androidLibraryData.getCompileJarFiles().stream()
                            .filter(File::exists)
                            .forEach(impl::addLibrary);


                    // TODO: add res index support
                    break;
            }

            // remember this dependency so that there will be no circular dependencies
            visitedDependencies.add(compileDependency);

            // now add its dependencies to the queue if it hasn't been visited before
            for (GraphItem dependency : compileDependency.getDependencies()) {
                if (!visitedDependencies.contains(dependency)) {
                    queue.addLast(dependency);
                }
            }
        }

        currentProject.addModule(impl);
        indexModule(impl);
    }

    /**
     * Indexes each module so completion would work immediately
     * <p>
     * In-order to keep indexing as fast as possible, method bodies of each classes are removed.
     * When the file is opened in the editor, its contents will be re-parsed with method bodies
     * included.
     */
    private void indexModule(Module module) throws IOException {
        module.open();
        module.index();

        JavaModule javaModule = (JavaModule) module;
        for (File value : javaModule.getJavaFiles().values()) {
            CompilationInfo info = CompilationInfo.get(module.getProject(), value);
            if (info == null) {
                continue;
            }
            info.updateImmediately(new SimpleJavaFileObject(value.toURI(),
                    JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    Parser parser = Parser.parseFile(module.getProject(), value.toPath());
                    // During indexing, statements inside methods are not needed so
                    // it is stripped to speed up the index process
                    return new PruneMethodBodies(info.impl.getJavacTask()).scan(parser.root, 0L);
                }
            });
        }
    }

    public void closeProject(@NonNull Project project) {
        if (project.equals(mCurrentProject)) {
            mCurrentProject = null;
        }
    }

    public synchronized Project getCurrentProject() {
        return mCurrentProject;
    }

    public static File createFile(File directory,
                                  String name,
                                  CodeTemplate template) throws IOException {
        if (!directory.isDirectory()) {
            return null;
        }

        String code = template.get().replace(CodeTemplate.CLASS_NAME, name);

        File classFile = new File(directory, name + template.getExtension());
        if (classFile.exists()) {
            return null;
        }
        if (!classFile.createNewFile()) {
            return null;
        }

        FileUtils.writeStringToFile(classFile, code, Charsets.UTF_8);
        return classFile;
    }

    @Nullable
    public static File createClass(File directory,
                                   String className,
                                   CodeTemplate template) throws IOException {
        if (!directory.isDirectory()) {
            return null;
        }

        String packageName = ProjectUtils.getPackageName(directory);
        if (packageName == null) {
            return null;
        }

        String code = template.get()
                .replace(CodeTemplate.PACKAGE_NAME, packageName)
                .replace(CodeTemplate.CLASS_NAME, className);

        File classFile = new File(directory, className + template.getExtension());
        if (classFile.exists()) {
            return null;
        }
        if (!classFile.createNewFile()) {
            return null;
        }

        FileUtils.writeStringToFile(classFile, code, Charsets.UTF_8);
        return classFile;
    }
}
