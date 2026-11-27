package com.garan.drytoast.ui.friction

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.garan.drytoast.R
import com.garan.drytoast.core.friction.TypedIntentionSession
import com.garan.drytoast.core.port.HapticEngine
import com.garan.drytoast.core.port.NoOpHapticEngine
import com.garan.drytoast.ui.motion.DryToastMotion
import com.garan.drytoast.ui.theme.AmoledBlack
import com.garan.drytoast.ui.theme.PureWhite
import com.garan.drytoast.ui.theme.SubtleGray
import kotlin.random.Random

private val MAX_TYPED_INTENTION_WIDTH = 600.dp

/**
 * Full-screen Typed Intention anti-impulse checkpoint for guarded applications.
 *
 * Implements the mechanical lock-tumbler sensory contract:
 * - Plain black surface with edge-to-edge drawing and keyboard insets padding.
 * - Target phrase displayed in [SubtleGray], with matched characters illuminating to [PureWhite].
 * - Wrong characters are rejected with a firm resistance detent haptic without polluting the display.
 * - "Go back" button morphs into "Open" with a target snap when the phrase is complete.
 */
@Composable
fun TypedIntentionSurface(
    appLabel: String,
    onConfirmOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hapticEngine: HapticEngine = NoOpHapticEngine,
    overridePhrase: String? = null,
    embedded: Boolean = false,
) {
    BackHandler(enabled = !embedded) {
        onDismiss()
    }

    val context = LocalContext.current
    val targetPhrase = rememberSaveable(appLabel, overridePhrase) {
        if (overridePhrase != null) {
            overridePhrase
        } else {
            val phrases = context.resources.getStringArray(R.array.typed_intention_phrases)
            val selected = phrases[Random.nextInt(phrases.size)]
            if (selected.contains("%s")) {
                String.format(selected, appLabel)
            } else {
                selected
            }
        }
    }

    var matchedCount by rememberSaveable(targetPhrase) { mutableIntStateOf(0) }
    val session = remember(targetPhrase) {
        TypedIntentionSession(targetPhrase = targetPhrase, initialMatchedCount = matchedCount)
    }
    val isComplete = matchedCount >= targetPhrase.length

    val keyboardController = LocalSoftwareKeyboardController.current
    val compactHeight = !embedded && LocalConfiguration.current.screenHeightDp < 440
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(embedded) {
        if (!embedded) {
            focusRequester.requestFocus()
        }
    }

    var textFieldValue by remember(session) {
        mutableStateOf(
            TextFieldValue(
                text = session.matchedText,
                selection = TextRange(session.matchedText.length),
            ),
        )
    }

    val baseLabelSmall = MaterialTheme.typography.labelSmall
    val headerStyle = remember(baseLabelSmall) {
        baseLabelSmall.copy(
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }

    val containerModifier = if (embedded) {
        modifier
            .fillMaxWidth()
            .background(AmoledBlack)
            .pointerInput(Unit) {
                detectTapGestures {
                    focusRequester.requestFocus()
                }
            }
            .padding(horizontal = 16.dp, vertical = 20.dp)
    } else {
        modifier
            .fillMaxSize()
            .background(AmoledBlack)
            .safeDrawingPadding()
            .imePadding()
            .pointerInput(Unit) {
                detectTapGestures {
                    focusRequester.requestFocus()
                }
            }
            .padding(horizontal = 24.dp, vertical = if (compactHeight) 8.dp else 24.dp)
    }

    val columnModifier = if (embedded) {
        Modifier
            .widthIn(max = MAX_TYPED_INTENTION_WIDTH)
            .fillMaxWidth()
    } else {
        Modifier
            .widthIn(max = MAX_TYPED_INTENTION_WIDTH)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    }

    Box(
        modifier = containerModifier,
        contentAlignment = if (embedded) Alignment.CenterStart else Alignment.Center,
    ) {
        Column(
            modifier = columnModifier.testTag("typed_intention_content_column"),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.friction_typed_intention_header),
                style = headerStyle,
                color = SubtleGray,
            )

            Spacer(modifier = Modifier.height(if (compactHeight) 6.dp else 16.dp))

            // Dual-tone illuminated phrase: matched text is PureWhite, remaining is SubtleGray
            val annotatedPhrase = remember(matchedCount, targetPhrase) {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = PureWhite)) {
                        append(session.matchedText)
                    }
                    withStyle(SpanStyle(color = SubtleGray)) {
                        append(session.remainingText)
                    }
                }
            }

            Text(
                text = annotatedPhrase,
                style = MaterialTheme.typography.headlineMedium,
                lineHeight = 36.sp,
                modifier = Modifier.testTag("typed_intention_phrase"),
            )

            Spacer(modifier = Modifier.height(if (compactHeight) 8.dp else 22.dp))

            // Action Button: "Go back" (SubtleGray) morphs into "Open" (PureWhite) with >= 48dp touch bounds
            AnimatedContent(
                targetState = isComplete,
                transitionSpec = {
                    fadeIn(
                        animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_OPEN_DURATION_MS),
                    ) togetherWith
                        fadeOut(animationSpec = DryToastMotion.opticalTween(DryToastMotion.SHUTTER_CLOSE_DURATION_MS))
                },
                label = "ActionButtonTransition",
            ) { complete ->
                Box(
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag("typed_intention_action_button")
                        .clickable(role = Role.Button) {
                            if (complete) {
                                onConfirmOpen()
                            } else {
                                onDismiss()
                            }
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = stringResource(if (complete) R.string.action_open else R.string.action_go_back),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (complete) PureWhite else SubtleGray,
                    )
                }
            }
        }

        // Invisible text field capturing keyboard strokes with suggestions disabled
        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                if (!session.isComplete) {
                    val newText = newValue.text
                    val oldText = session.matchedText

                    if (newText.length > oldText.length) {
                        val deltaCount = newText.length - oldText.length
                        if (deltaCount > 2) {
                            // Reject bulk clipboard paste or whole-word autocommit
                            hapticEngine.resistanceDetent()
                        } else {
                            val charsToProcess = if (newText.startsWith(oldText)) {
                                newText.substring(oldText.length)
                            } else {
                                newText.takeLast(1)
                            }
                            for (c in charsToProcess) {
                                when (session.typeChar(c)) {
                                    TypedIntentionSession.MatchResult.Matched -> {
                                        matchedCount = session.matchedCount
                                    }
                                    TypedIntentionSession.MatchResult.Completed -> {
                                        matchedCount = session.matchedCount
                                        hapticEngine.targetSnap()
                                        if (!embedded) {
                                            keyboardController?.hide()
                                        }
                                        break
                                    }
                                    TypedIntentionSession.MatchResult.Mismatch -> {
                                        hapticEngine.resistanceDetent()
                                        break
                                    }
                                }
                            }
                        }
                    } else if (newText.length < oldText.length) {
                        val deleteCount = oldText.length - newText.length
                        repeat(deleteCount) {
                            session.backspace()
                        }
                        matchedCount = session.matchedCount
                    }
                }

                val currentMatched = session.matchedText
                textFieldValue = TextFieldValue(
                    text = currentMatched,
                    selection = TextRange(currentMatched.length),
                )
            },
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .testTag("typed_intention_input")
                .focusRequester(focusRequester),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = if (isComplete) ImeAction.Go else ImeAction.None,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    if (session.isComplete) {
                        onConfirmOpen()
                    }
                },
            ),
        )
    }
}
