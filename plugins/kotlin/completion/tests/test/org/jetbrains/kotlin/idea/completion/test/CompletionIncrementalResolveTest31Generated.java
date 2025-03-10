// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.completion.test;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.idea.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.idea.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.idea.test.TestRoot;
import org.junit.runner.RunWith;

/**
 * This class is generated by {@link org.jetbrains.kotlin.testGenerator.generator.TestGenerator}.
 * DO NOT MODIFY MANUALLY.
 */
@SuppressWarnings("all")
@TestRoot("completion/tests")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
@TestMetadata("testData/incrementalResolve")
public class CompletionIncrementalResolveTest31Generated extends AbstractCompletionIncrementalResolveTest31 {
    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTest, this, testDataFilePath);
    }

    @TestMetadata("codeAboveChanged.kt")
    public void testCodeAboveChanged() throws Exception {
        runTest("testData/incrementalResolve/codeAboveChanged.kt");
    }

    @TestMetadata("codeAboveChanged2.kt")
    public void testCodeAboveChanged2() throws Exception {
        runTest("testData/incrementalResolve/codeAboveChanged2.kt");
    }

    @TestMetadata("dataFlowInfoFromPrevStatement.kt")
    public void testDataFlowInfoFromPrevStatement() throws Exception {
        runTest("testData/incrementalResolve/dataFlowInfoFromPrevStatement.kt");
    }

    @TestMetadata("dataFlowInfoFromSameStatement.kt")
    public void testDataFlowInfoFromSameStatement() throws Exception {
        runTest("testData/incrementalResolve/dataFlowInfoFromSameStatement.kt");
    }

    @TestMetadata("doNotAnalyzeComplexStatement.kt")
    public void testDoNotAnalyzeComplexStatement() throws Exception {
        runTest("testData/incrementalResolve/doNotAnalyzeComplexStatement.kt");
    }

    @TestMetadata("noDataFlowFromOldStatement.kt")
    public void testNoDataFlowFromOldStatement() throws Exception {
        runTest("testData/incrementalResolve/noDataFlowFromOldStatement.kt");
    }

    @TestMetadata("noPrevStatement.kt")
    public void testNoPrevStatement() throws Exception {
        runTest("testData/incrementalResolve/noPrevStatement.kt");
    }

    @TestMetadata("outOfBlockModification.kt")
    public void testOutOfBlockModification() throws Exception {
        runTest("testData/incrementalResolve/outOfBlockModification.kt");
    }

    @TestMetadata("prevStatementNotResolved.kt")
    public void testPrevStatementNotResolved() throws Exception {
        runTest("testData/incrementalResolve/prevStatementNotResolved.kt");
    }

    @TestMetadata("sameStatement.kt")
    public void testSameStatement() throws Exception {
        runTest("testData/incrementalResolve/sameStatement.kt");
    }
}
