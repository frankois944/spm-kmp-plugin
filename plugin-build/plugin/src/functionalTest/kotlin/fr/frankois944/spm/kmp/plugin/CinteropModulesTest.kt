package fr.frankois944.spm.kmp.plugin

import com.autonomousapps.kit.GradleBuilder.build
import com.autonomousapps.kit.truth.TestKitTruth.Companion.assertThat
import fr.frankois944.spm.kmp.plugin.fixture.KotlinSource
import fr.frankois944.spm.kmp.plugin.fixture.SmpKMPTestFixture
import fr.frankois944.spm.kmp.plugin.fixture.SwiftSource
import fr.frankois944.spm.kmp.plugin.utils.assumeMacos
import org.junit.jupiter.api.Test

class CinteropModulesTest {
    @Test
    fun `build with imported UIKit framework is successful`() {
        assumeMacos()

        // Given
        val fixture =
            SmpKMPTestFixture
                .builder()
                .withSwiftSources(
                    SwiftSource.of(
                        content =
                            """
                            import UIKit
                            @objc public class TestView: UIView {}
                            """.trimIndent(),
                    ),
                ).withKotlinSources(
                    KotlinSource.of(
                        content =
                            """
                            package com.example
                            import dummy.TestView
                            @kotlinx.cinterop.ExperimentalForeignApi
                            val view = TestView()
                            """.trimIndent(),
                    ),
                ).build()

        // When
        val result = build(fixture.gradleProject.rootDir, "build")

        // Then
        assertThat(result).task(":library:build").succeeded()
    }
}
