// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.maven;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PsiTestUtil;
import com.intellij.testFramework.RunAll;
import com.intellij.testFramework.UsefulTestCase;
import com.intellij.testFramework.fixtures.IdeaProjectTestFixture;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import com.intellij.util.ThrowableRunnable;
import com.intellij.util.containers.CollectionFactory;
import com.intellij.util.ui.UIUtil;
import org.intellij.lang.annotations.Language;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.project.*;
import org.jetbrains.idea.maven.server.MavenServerManager;
import org.jetbrains.kotlin.idea.test.KotlinTestUtils;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;

public abstract class MavenTestCase extends UsefulTestCase {

    private static final String mavenMirrorUrl = System.getProperty("idea.maven.test.mirror",
                                                                    // use JB maven proxy server for internal use by default, see details at
                                                                    // https://confluence.jetbrains.com/display/JBINT/Maven+proxy+server
                                                                    "https://maven.labs.intellij.net/repo1");
    private static boolean mirrorDiscoverable = false;

    static {
        try {
            URL url = new URL(mavenMirrorUrl);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setConnectTimeout(1000);
            int responseCode = urlConnection.getResponseCode();
            if (responseCode < 400) {
                mirrorDiscoverable = true;
            }
        }
        catch (Exception e) {
            mirrorDiscoverable = false;
        }
    }

    protected IdeaProjectTestFixture myTestFixture;

    protected Project myProject;

    protected File myDir;
    protected VirtualFile myProjectRoot;

    protected VirtualFile myProjectPom;
    protected List<VirtualFile> myAllPoms = new ArrayList<VirtualFile>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        myDir = KotlinTestUtils.tmpDir(getTestName(false));

        setUpFixtures();

        myProject = myTestFixture.getProject();

        MavenWorkspaceSettingsComponent.getInstance(myProject).loadState(new MavenWorkspaceSettings());

        String home = getTestMavenHome();
        if (home != null) {
            getMavenGeneralSettings().setMavenHome(home);
        }

        UIUtil.invokeAndWaitIfNeeded((Runnable) () -> {
            try {
                restoreSettingsFile();
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }

            ApplicationManager.getApplication().runWriteAction(() -> {
                try {
                    setUpInWriteAction();
                }
                catch (Throwable e) {
                    try {
                        tearDown();
                    }
                    catch (Exception e1) {
                        e1.printStackTrace();
                    }
                    throw new RuntimeException(e);
                }
            });
        });
    }

    protected void setUpFixtures() throws Exception {
        myTestFixture = IdeaTestFixtureFactory.getFixtureFactory().createFixtureBuilder(getName()).getFixture();
        myTestFixture.setUp();
    }

    protected void setUpInWriteAction() throws Exception {
        File projectDir = FileUtil.createTempDirectory("project", "", false);
        myProjectRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(projectDir);
    }

    @Override
    protected void tearDown() {
        RunAll.runAll(
                () -> MavenServerManager.getInstance().shutdown(true),
                () -> MavenArtifactDownloader.awaitQuiescence(100, TimeUnit.SECONDS),
                () -> myProject = null,
                () -> UIUtil.invokeAndWaitIfNeeded((Runnable) () -> {
                    try {
                        tearDownFixtures();
                    }
                    catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }),
                () -> super.tearDown(),
                () -> resetClassFields(getClass())
        );
    }

    private static void printDirectoryContent(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File file : files) {
            System.out.println(file.getAbsolutePath());

            if (file.isDirectory()) {
                printDirectoryContent(file);
            }
        }
    }

    protected void tearDownFixtures() {
        RunAll.runAll(
                () -> myTestFixture.tearDown(),
                () -> myTestFixture = null
        );
    }

    private void resetClassFields(final Class<?> aClass) {
        if (aClass == null) return;

        final Field[] fields = aClass.getDeclaredFields();
        for (Field field : fields) {
            final int modifiers = field.getModifiers();
            if ((modifiers & Modifier.FINAL) == 0
                && (modifiers & Modifier.STATIC) == 0
                && !field.getType().isPrimitive()) {
                field.setAccessible(true);
                try {
                    field.set(this, null);
                }
                catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }

        if (aClass == MavenTestCase.class) return;
        resetClassFields(aClass.getSuperclass());
    }

    @Override
    protected void runTestRunnable(@NotNull ThrowableRunnable<Throwable> testRunnable) throws Throwable {
        try {
            super.runTestRunnable(testRunnable);
        }
        catch (Exception throwable) {
            Throwable each = throwable;
            do {
                if (each instanceof HeadlessException) {
                    printIgnoredMessage("Doesn't work in Headless environment");
                    return;
                }
            }
            while ((each = each.getCause()) != null);
            throw throwable;
        }
    }

    protected static String getRoot() {
        if (SystemInfo.isWindows) return "c:";
        return "";
    }

    protected static String getEnvVar() {
        if (SystemInfo.isWindows) {
            return "TEMP";
        }
        else if (SystemInfo.isLinux) return "HOME";
        return "TMPDIR";
    }

    protected MavenGeneralSettings getMavenGeneralSettings() {
        return MavenProjectsManager.getInstance(myProject).getGeneralSettings();
    }

    protected MavenImportingSettings getMavenImporterSettings() {
        return MavenProjectsManager.getInstance(myProject).getImportingSettings();
    }

    protected String getRepositoryPath() {
        String path = getRepositoryFile().getPath();
        return FileUtil.toSystemIndependentName(path);
    }

    protected File getRepositoryFile() {
        return getMavenGeneralSettings().getEffectiveLocalRepository();
    }

    protected void setRepositoryPath(String path) {
        getMavenGeneralSettings().setLocalRepository(path);
    }

    protected String getProjectPath() {
        return myProjectRoot.getPath();
    }

    protected String getParentPath() {
        return myProjectRoot.getParent().getPath();
    }

    protected String pathFromBasedir(String relPath) {
        return pathFromBasedir(myProjectRoot, relPath);
    }

    protected static String pathFromBasedir(VirtualFile root, String relPath) {
        return FileUtil.toSystemIndependentName(root.getPath() + "/" + relPath);
    }

    protected VirtualFile updateSettingsXml(String content) throws IOException {
        return updateSettingsXmlFully(createSettingsXmlContent(content));
    }

    protected VirtualFile updateSettingsXmlFully(@NonNls @Language("XML") String content) throws IOException {
        File ioFile = new File(myDir, "settings.xml");
        ioFile.createNewFile();
        VirtualFile f = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile);
        setFileContent(f, content, true);
        getMavenGeneralSettings().setUserSettingsFile(f.getPath());
        return f;
    }

    protected void deleteSettingsXml() throws IOException {
        WriteAction.runAndWait(() -> {
            VirtualFile f = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(new File(myDir, "settings.xml"));
            if (f != null) f.delete(this);
        });
    }

    private static String createSettingsXmlContent(String content) {

        if (!mirrorDiscoverable) {
            System.err.println("Maven mirror at " + mavenMirrorUrl + " not reachable, so not using it.");

            return "<settings>" +
                   content +
                   "</settings>";
        }

        System.out.println("Using Maven mirror at " + mavenMirrorUrl);
        return "<settings>" +
               content +
               "<mirrors>" +
               "  <mirror>" +
               "    <id>jb-central-proxy</id>" +
               "    <url>" + mavenMirrorUrl + "</url>" +
               "    <mirrorOf>external:*</mirrorOf>" +
               "  </mirror>" +
               "</mirrors>" +
               "</settings>";
    }

    protected void restoreSettingsFile() throws IOException {
        updateSettingsXml("");
    }

    protected Module createModule(String name) throws IOException {
        return createModule(name, StdModuleTypes.JAVA);
    }

    protected Module createModule(final String name, final ModuleType type) throws IOException {
        return WriteAction.computeAndWait(() -> {
            VirtualFile f = createProjectSubFile(name + "/" + name + ".iml");
            Module module = ModuleManager.getInstance(myProject).newModule(f.getPath(), type.getId());
            PsiTestUtil.addContentRoot(module, f.getParent());
            return module;
        });
    }

    protected VirtualFile createProjectPom(@NonNls String xml) throws IOException {
        return myProjectPom = createPomFile(myProjectRoot, xml);
    }

    protected VirtualFile createModulePom(String relativePath, String xml) throws IOException {
        return createPomFile(createProjectSubDir(relativePath), xml);
    }

    protected VirtualFile createPomFile(final VirtualFile dir, String xml) throws IOException {
        VirtualFile f = dir.findChild("pom.xml");
        if (f == null) {
            f = WriteAction.computeAndWait(() -> dir.createChildData(null, "pom.xml"));
            myAllPoms.add(f);
        }
        setFileContent(f, createPomXml(xml), true);
        return f;
    }

    @NonNls
    @Language(value = "XML")
    public static String createPomXml(@NonNls @Language(value = "XML", prefix = "<xml>", suffix = "</xml>") String xml) {
        return "<?xml version=\"1.0\"?>" +
               "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"" +
               "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"" +
               "         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd\">" +
               "  <modelVersion>4.0.0</modelVersion>" +
               xml +
               "</project>";
    }

    protected VirtualFile createProfilesXmlOldStyle(String xml) throws IOException {
        return createProfilesFile(myProjectRoot, xml, true);
    }

    protected VirtualFile createProfilesXmlOldStyle(String relativePath, String xml) throws IOException {
        return createProfilesFile(createProjectSubDir(relativePath), xml, true);
    }

    protected VirtualFile createProfilesXml(String xml) throws IOException {
        return createProfilesFile(myProjectRoot, xml, false);
    }

    protected VirtualFile createProfilesXml(String relativePath, String xml) throws IOException {
        return createProfilesFile(createProjectSubDir(relativePath), xml, false);
    }

    private static VirtualFile createProfilesFile(VirtualFile dir, String xml, boolean oldStyle) throws IOException {
        return createProfilesFile(dir, createValidProfiles(xml, oldStyle));
    }

    protected VirtualFile createFullProfilesXml(String content) throws IOException {
        return createProfilesFile(myProjectRoot, content);
    }

    protected VirtualFile createFullProfilesXml(String relativePath, String content) throws IOException {
        return createProfilesFile(createProjectSubDir(relativePath), content);
    }

    private static VirtualFile createProfilesFile(final VirtualFile dir, String content) throws IOException {
        VirtualFile f = dir.findChild("profiles.xml");
        if (f == null) {
            f = WriteAction.computeAndWait(() -> dir.createChildData(null, "profiles.xml"));
        }
        setFileContent(f, content, true);
        return f;
    }

    @Language("XML")
    private static String createValidProfiles(String xml, boolean oldStyle) {
        if (oldStyle) {
            return "<?xml version=\"1.0\"?>" +
                   "<profiles>" +
                   xml +
                   "</profiles>";
        }
        return "<?xml version=\"1.0\"?>" +
               "<profilesXml>" +
               "<profiles>" +
               xml +
               "</profiles>" +
               "</profilesXml>";
    }

    protected void deleteProfilesXml() throws IOException {
        WriteAction.runAndWait(() -> {
            VirtualFile f = myProjectRoot.findChild("profiles.xml");
            if (f != null) f.delete(this);
        });
    }

    protected void createStdProjectFolders() {
        createProjectSubDirs("src/main/java",
                             "src/main/resources",
                             "src/test/java",
                             "src/test/resources");
    }

    protected void createProjectSubDirs(String... relativePaths) {
        for (String path : relativePaths) {
            createProjectSubDir(path);
        }
    }

    protected VirtualFile createProjectSubDir(String relativePath) {
        File f = new File(getProjectPath(), relativePath);
        f.mkdirs();
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
    }

    protected VirtualFile createProjectSubFile(String relativePath) throws IOException {
        File f = new File(getProjectPath(), relativePath);
        f.getParentFile().mkdirs();
        f.createNewFile();
        return LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
    }

    protected VirtualFile createProjectSubFile(String relativePath, String content) throws IOException {
        VirtualFile file = createProjectSubFile(relativePath);
        setFileContent(file, content, false);
        return file;
    }

    private static void setFileContent(final VirtualFile file, final String content, final boolean advanceStamps) throws IOException {
        WriteAction.runAndWait(() -> {
            if (advanceStamps) {
                file.setBinaryContent(content.getBytes(), -1, file.getTimeStamp() + 4000);
            }
            else {
                file.setBinaryContent(content.getBytes(), file.getModificationStamp(), file.getTimeStamp());
            }
        });
    }

    protected static <T, U> void assertOrderedElementsAreEqual(Collection<U> actual, Collection<T> expected) {
        assertOrderedElementsAreEqual(actual, expected.toArray());
    }

    protected static <T> void assertUnorderedElementsAreEqual(Collection<T> actual, Collection<T> expected) {
        assertEquals(new HashSet<T>(expected), new HashSet<T>(actual));
    }

    protected static void assertUnorderedPathsAreEqual(Collection<String> actual, Collection<String> expected) {
        assertEquals(new SetWithToString<String>(CollectionFactory.createFilePathSet(expected)),
                     new SetWithToString<String>(CollectionFactory.createFilePathSet(actual)));
    }

    protected static <T> void assertUnorderedElementsAreEqual(T[] actual, T... expected) {
        assertUnorderedElementsAreEqual(Arrays.asList(actual), expected);
    }

    protected static <T> void assertUnorderedElementsAreEqual(Collection<T> actual, T... expected) {
        assertUnorderedElementsAreEqual(actual, Arrays.asList(expected));
    }

    protected static <T, U> void assertOrderedElementsAreEqual(Collection<U> actual, T... expected) {
        String s = "\nexpected: " + Arrays.asList(expected) + "\nactual: " + new ArrayList<U>(actual);
        assertEquals(s, expected.length, actual.size());

        List<U> actualList = new ArrayList<U>(actual);
        for (int i = 0; i < expected.length; i++) {
            T expectedElement = expected[i];
            U actualElement = actualList.get(i);
            assertEquals(s, expectedElement, actualElement);
        }
    }

    protected static <T> void assertContain(List<? extends T> actual, T... expected) {
        List<T> expectedList = Arrays.asList(expected);
        assertTrue("expected: " + expectedList + "\n" + "actual: " + actual.toString(), actual.containsAll(expectedList));
    }

    protected static <T> void assertDoNotContain(List<T> actual, T... expected) {
        List<T> actualCopy = new ArrayList<T>(actual);
        actualCopy.removeAll(Arrays.asList(expected));
        assertEquals(actual.toString(), actualCopy.size(), actual.size());
    }

    protected boolean ignore() {
        printIgnoredMessage(null);
        return true;
    }

    protected boolean hasMavenInstallation() {
        boolean result = getTestMavenHome() != null;
        if (!result) printIgnoredMessage("Maven installation not found");
        return result;
    }

    private void printIgnoredMessage(String message) {
        String toPrint = "Ignored";
        if (message != null) {
            toPrint += ", because " + message;
        }
        toPrint += ": " + getClass().getSimpleName() + "." + getName();
        System.out.println(toPrint);
    }

    private static String getTestMavenHome() {
        return System.getProperty("idea.maven.test.home");
    }

    private static class SetWithToString<T> extends AbstractSet<T> {

        private final Set<T> myDelegate;

        public SetWithToString(@NotNull Set<T> delegate) {
            myDelegate = delegate;
        }

        @Override
        public int size() {
            return myDelegate.size();
        }

        @Override
        public boolean contains(Object o) {
            return myDelegate.contains(o);
        }

        @NotNull
        @Override
        public Iterator<T> iterator() {
            return myDelegate.iterator();
        }

        @Override
        public boolean containsAll(Collection<?> c) {
            return myDelegate.containsAll(c);
        }

        @Override
        public boolean equals(Object o) {
            return myDelegate.equals(o);
        }

        @Override
        public int hashCode() {
            return myDelegate.hashCode();
        }
    }
}
