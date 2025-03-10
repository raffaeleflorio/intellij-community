// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.kotlin.idea.inspections.collections

import org.jetbrains.annotations.NonNls
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.inspections.AbstractKotlinInspection
import org.jetbrains.kotlin.idea.project.languageVersionSettings
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtReturnExpression
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.DefaultValueArgument
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall

abstract class AbstractCallChainChecker : AbstractKotlinInspection() {

    protected fun findQualifiedConversion(
        expression: KtQualifiedExpression,
        conversionGroups: Map<ConversionId, List<Conversion>>,
        additionalCallCheck: (Conversion, ResolvedCall<*>, ResolvedCall<*>, BindingContext) -> Boolean
    ): Conversion? {
        val firstExpression = expression.receiverExpression
        val firstCallExpression = getCallExpression(firstExpression) ?: return null
        val firstCalleeExpression = firstCallExpression.calleeExpression ?: return null

        val secondCallExpression = expression.selectorExpression as? KtCallExpression ?: return null

        val secondCalleeExpression = secondCallExpression.calleeExpression ?: return null
        val apiVersion by lazy { expression.languageVersionSettings.apiVersion }
        val actualConversions = conversionGroups[ConversionId(firstCalleeExpression, secondCalleeExpression)]?.filter {
            it.replaceableApiVersion == null || apiVersion >= it.replaceableApiVersion
        }?.sortedByDescending { it.removeNotNullAssertion } ?: return null

        val context = expression.analyze()
        val firstResolvedCall = firstExpression.getResolvedCall(context) ?: return null
        val secondResolvedCall = expression.getResolvedCall(context) ?: return null
        val conversion = actualConversions.firstOrNull {
            firstResolvedCall.isCalling(FqName(it.firstFqName)) && additionalCallCheck(it, firstResolvedCall, secondResolvedCall, context)
        } ?: return null

        // Do not apply for lambdas with return inside
        val lambdaArgument = firstCallExpression.lambdaArguments.firstOrNull()
        if (lambdaArgument?.anyDescendantOfType<KtReturnExpression>() == true) return null

        if (!secondResolvedCall.isCalling(FqName(conversion.secondFqName))) return null
        if (secondResolvedCall.valueArguments.any { (parameter, resolvedArgument) ->
                parameter.type.isFunctionOfAnyKind() &&
                        resolvedArgument !is DefaultValueArgument
            }
        ) return null

        return conversion
    }

    protected fun List<Conversion>.group(): Map<ConversionId, List<Conversion>> =
        groupBy { conversion -> conversion.id }

    data class ConversionId(val firstFqName: String, val secondFqName: String) {
        constructor(first: KtExpression, second: KtExpression) : this(first.text, second.text)
    }

    data class Conversion(
        @NonNls val firstFqName: String,
        @NonNls val secondFqName: String,
        @NonNls val replacement: String,
        @NonNls val additionalArgument: String? = null,
        val addNotNullAssertion: Boolean = false,
        val enableSuspendFunctionCall: Boolean = true,
        val removeNotNullAssertion: Boolean = false,
        val replaceableApiVersion: ApiVersion? = null,
    ) {
        private fun String.convertToShort() = takeLastWhile { it != '.' }

        val id: ConversionId get() = ConversionId(firstName, secondName)

        val firstName = firstFqName.convertToShort()

        val secondName = secondFqName.convertToShort()

        fun withArgument(argument: String) = Conversion(firstFqName, secondFqName, replacement, argument)
    }

    companion object {
        fun KtQualifiedExpression.firstCalleeExpression(): KtExpression? {
            val firstExpression = receiverExpression
            val firstCallExpression = getCallExpression(firstExpression) ?: return null
            return firstCallExpression.calleeExpression
        }

        fun getCallExpression(firstExpression: KtExpression) =
            (firstExpression as? KtQualifiedExpression)?.selectorExpression as? KtCallExpression
                ?: firstExpression as? KtCallExpression
    }
}