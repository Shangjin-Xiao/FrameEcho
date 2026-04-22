package com.shangjin.frameecho.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.components.TooltipWrapper

/**
 * Defines the position of a tooltip bubble relative to its anchor.
 */
enum class BubblePosition {
    TOP,
    BOTTOM,
    CENTER
}

/**
 * A single step in the onboarding guide.
 *
 * @param key Stable unique key for this step, used for per-step persistence.
 */
data class OnboardingStep(
    val key: String,
    val titleResId: Int,
    val descriptionResId: Int,
    val icon: ImageVector,
    val bubblePosition: BubblePosition = BubblePosition.CENTER,
    val verticalBias: Float = 0f  // -1 = top, 0 = center, 1 = bottom
)

/**
 * Full-screen overlay that guides users through app features one bubble at a time.
 *
 * Only unseen steps are shown. Each step is displayed individually as a centered
 * tooltip bubble. When the user finishes or skips, the shown steps are marked as seen
 * so they won't reappear. New steps added in future updates will automatically appear.
 *
 * @param steps The list of unseen steps to display.
 * @param currentStep The current step index within [steps] (0-based).
 * @param onNextStep Callback when user taps "Next".
 * @param onSkip Callback when user taps "Skip" or close.
 * @param onFinish Callback when user finishes the last step.
 */
@Composable
fun OnboardingOverlay(
    steps: List<OnboardingStep>,
    currentStep: Int,
    onNextStep: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit
) {
    if (currentStep >= steps.size) return

    val step = steps[currentStep]
    val totalSteps = steps.size
    val isLastStep = currentStep == totalSteps - 1

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                // Tapping the overlay advances to next step
                if (isLastStep) onFinish() else onNextStep()
            }
    ) {
        // Skip button — top-right
        TooltipWrapper(
            label = stringResource(R.string.onboarding_skip),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 48.dp, end = 16.dp)
        ) {
            IconButton(
                onClick = onSkip
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.onboarding_skip),
                    tint = Color.White
                )
            }
        }

        // Step indicator — top-center
        StepIndicator(
            currentStep = currentStep,
            totalSteps = totalSteps,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 56.dp)
        )

        // Tooltip bubble card — positioned based on step config
        val verticalAlignment = when {
            step.verticalBias < -0.3f -> Alignment.TopCenter
            step.verticalBias > 0.3f -> Alignment.BottomCenter
            else -> Alignment.Center
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = if (step.verticalBias < -0.3f) 100.dp else 0.dp,
                    bottom = if (step.verticalBias > 0.3f) 100.dp else 0.dp
                ),
            contentAlignment = verticalAlignment
        ) {
            TooltipBubble(
                step = step,
                stepNumber = currentStep + 1,
                totalSteps = totalSteps,
                isLastStep = isLastStep,
                onNext = if (isLastStep) onFinish else onNextStep,
                onSkip = onSkip
            )
        }
    }
}

/**
 * Animated tooltip bubble card showing the current step's info.
 */
@Composable
private fun TooltipBubble(
    step: OnboardingStep,
    stepNumber: Int,
    totalSteps: Int,
    isLastStep: Boolean,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    // Pulse animation for the icon
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    AnimatedVisibility(
        visible = true,
        enter = scaleIn(
            initialScale = 0.8f,
            animationSpec = tween(300, easing = FastOutSlowInEasing)
        ) + fadeIn(animationSpec = tween(300)),
        exit = scaleOut(targetScale = 0.8f) + fadeOut()
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .padding(horizontal = 24.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(20.dp)
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { /* consume click — don't pass through to overlay */ },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Animated icon
                Surface(
                    modifier = Modifier
                        .size((56 * pulseScale).dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            step.icon,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title
                Text(
                    text = stringResource(step.titleResId),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Description
                Text(
                    text = stringResource(step.descriptionResId),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isLastStep) {
                        TextButton(onClick = onSkip) {
                            Text(
                                text = stringResource(R.string.onboarding_skip),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    FilledTonalButton(onClick = onNext) {
                        Text(
                            text = stringResource(
                                if (isLastStep) R.string.onboarding_got_it
                                else R.string.onboarding_next
                            )
                        )
                        if (!isLastStep) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.NavigateNext,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dot-style step indicator.
 */
@Composable
private fun StepIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(totalSteps) { index ->
            val isActive = index == currentStep
            Box(
                modifier = Modifier
                    .size(if (isActive) 10.dp else 6.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) Color.White
                        else Color.White.copy(alpha = 0.4f)
                    )
            )
        }
    }
}

/** Keys for steps that existed before the versioned onboarding system. */
val LEGACY_ONBOARDING_STEP_KEYS: Set<String> = setOf(
    "capture", "mute", "motion_photo", "metadata", "format", "slider", "gestures"
)

/**
 * Creates and remembers the full ordered list of onboarding steps.
 * Each step has a stable [OnboardingStep.key] for per-step persistence.
 */
@Composable
fun rememberAllOnboardingSteps(): List<OnboardingStep> {
    return remember {
        listOf(
            // Step 1: Welcome / Capture button
            OnboardingStep(
                key = "capture",
                titleResId = R.string.onboarding_capture_title,
                descriptionResId = R.string.onboarding_capture_desc,
                icon = Icons.Default.CameraAlt,
                bubblePosition = BubblePosition.CENTER,
                verticalBias = 0f
            ),
            // Step 2: Mute toggle
            OnboardingStep(
                key = "mute",
                titleResId = R.string.onboarding_mute_title,
                descriptionResId = R.string.onboarding_mute_desc,
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                bubblePosition = BubblePosition.TOP,
                verticalBias = 0.4f
            ),
            // Step 3: Motion photo toggle
            OnboardingStep(
                key = "motion_photo",
                titleResId = R.string.onboarding_motion_title,
                descriptionResId = R.string.onboarding_motion_desc,
                icon = Icons.Default.MotionPhotosOn,
                bubblePosition = BubblePosition.TOP,
                verticalBias = 0.4f
            ),
            // Step 4: Metadata toggle
            OnboardingStep(
                key = "metadata",
                titleResId = R.string.onboarding_metadata_title,
                descriptionResId = R.string.onboarding_metadata_desc,
                icon = Icons.Default.Info,
                bubblePosition = BubblePosition.TOP,
                verticalBias = 0.4f
            ),
            // Step 5: Format quick-cycle
            OnboardingStep(
                key = "format",
                titleResId = R.string.onboarding_format_title,
                descriptionResId = R.string.onboarding_format_desc,
                icon = Icons.Default.Settings,
                bubblePosition = BubblePosition.TOP,
                verticalBias = 0.4f
            ),
            // Step 6: Seek slider & fine scrubbing
            OnboardingStep(
                key = "slider",
                titleResId = R.string.onboarding_slider_title,
                descriptionResId = R.string.onboarding_slider_desc,
                icon = Icons.Default.Swipe,
                bubblePosition = BubblePosition.TOP,
                verticalBias = 0.2f
            ),
            // Step 7: Frame step buttons (NEW)
            OnboardingStep(
                key = "frame_step",
                titleResId = R.string.onboarding_frame_step_title,
                descriptionResId = R.string.onboarding_frame_step_desc,
                icon = Icons.Default.SkipNext,
                bubblePosition = BubblePosition.TOP,
                verticalBias = 0.3f
            ),
            // Step 8: Video gestures (tap, pinch, zoom)
            OnboardingStep(
                key = "gestures",
                titleResId = R.string.onboarding_gestures_title,
                descriptionResId = R.string.onboarding_gestures_desc,
                icon = Icons.Default.TouchApp,
                bubblePosition = BubblePosition.CENTER,
                verticalBias = 0f
            )
        )
    }
}
