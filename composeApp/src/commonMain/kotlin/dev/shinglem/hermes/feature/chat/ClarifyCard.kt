package dev.shinglem.hermes.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shinglem.hermes.resources.Res
import dev.shinglem.hermes.resources.clarify_cancel
import dev.shinglem.hermes.resources.clarify_multi_hint
import dev.shinglem.hermes.resources.clarify_other
import dev.shinglem.hermes.resources.clarify_submit
import dev.shinglem.hermes.resources.clarify_title
import org.jetbrains.compose.resources.stringResource

/**
 * `clarify` 卡片：agent 提问，用户点选项或直接输入。
 *
 * 提交时每题都用 `qid` 作键；空串表示"这题跳过"。多选按后端能解析的逗号分隔形式提交
 * （`tools/clarify_tool.py::_parse_multi_select_response` 接受 list / JSON 数组 / 逗号分隔）。
 */
@Composable
fun ClarifyCard(
    clarify: PendingClarify,
    onAnswer: (answers: Map<String, String>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = remember(clarify.requestId) { mutableStateMapOf<String, Set<String>>() }
    val freeText = remember(clarify.requestId) { mutableStateMapOf<String, String>() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(Res.string.clarify_title),
                style = MaterialTheme.typography.labelLarge,
            )
            clarify.questions.forEach { question ->
                ClarifyQuestionBlock(question, selected, freeText)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(buildAnswers(clarify, selected, freeText)) }) {
                    Text(stringResource(Res.string.clarify_submit))
                }
                OutlinedButton(onClick = onCancel) {
                    Text(stringResource(Res.string.clarify_cancel))
                }
            }
        }
    }
}

@Composable
private fun ClarifyQuestionBlock(
    question: ClarifyQuestion,
    selected: MutableMap<String, Set<String>>,
    freeText: MutableMap<String, String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = question.question, style = MaterialTheme.typography.bodyMedium)
        if (question.multiSelect) {
            Text(
                text = stringResource(Res.string.clarify_multi_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
        }
        question.choices.forEach { choice ->
            val checked = selected[question.qid].orEmpty().contains(choice)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selected[question.qid] = toggle(question, selected[question.qid].orEmpty(), choice) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (question.multiSelect) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = {
                            selected[question.qid] = toggle(question, selected[question.qid].orEmpty(), choice)
                        },
                    )
                } else {
                    RadioButton(
                        selected = checked,
                        onClick = { selected[question.qid] = setOf(choice) },
                    )
                }
                Text(text = choice, style = MaterialTheme.typography.bodySmall)
            }
        }
        // 后端 UI 总会追加一行"其他（自己输入）"，这里保持同等能力；选项为空时就是纯输入框。
        OutlinedTextField(
            value = freeText[question.qid].orEmpty(),
            onValueChange = { freeText[question.qid] = it },
            placeholder = { Text(stringResource(Res.string.clarify_other)) },
            singleLine = question.choices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun toggle(question: ClarifyQuestion, current: Set<String>, choice: String): Set<String> =
    if (question.multiSelect) {
        if (choice in current) current - choice else current + choice
    } else {
        setOf(choice)
    }

private fun buildAnswers(
    clarify: PendingClarify,
    selected: Map<String, Set<String>>,
    freeText: Map<String, String>,
): Map<String, String> = clarify.questions.associate { question ->
    val free = freeText[question.qid]?.trim().orEmpty()
    val picked = selected[question.qid].orEmpty().toList()
    val value = when {
        question.multiSelect -> (picked + listOfNotNull(free.takeIf { it.isNotBlank() })).joinToString(", ")
        free.isNotBlank() -> free
        else -> picked.firstOrNull().orEmpty()
    }
    question.qid to value
}
