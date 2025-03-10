// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.fir.inspections

import org.jetbrains.kotlin.idea.codeInsight.AbstractInspectionTest

abstract class AbstractHLInspectionTest : AbstractInspectionTest() {
    override fun isFirPlugin() = true
    override fun inspectionClassDirective() = "// FIR_INSPECTION_CLASS:"
    override fun registerGradlPlugin() {}
}