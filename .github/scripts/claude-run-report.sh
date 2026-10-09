#!/usr/bin/env bash
# Turns the execution file that anthropics/claude-code-action writes (every SDK message of the
# session) into a readable report. The action itself only logs "full output hidden for security",
# so without this a run says nothing about what Claude did.
#
#   claude-run-report.sh <execution-file> [<notes-out>]
#
# The report has: the outcome, token usage per model, an activity summary (files read and
# changed, commands run), blocked tool calls, Claude's last message and the full list of tool
# calls. It goes to the job log and the run's summary page ($GITHUB_STEP_SUMMARY); given
# <notes-out>, a short Markdown excerpt is written there for a workflow to post on the issue.
#
# Tool output is only shown for failed calls, and clipped: the log masks secrets, and the
# repository is public.
set -euo pipefail

file=${1:-}
notes=${2:-}

if [ -z "$file" ] || [ ! -s "$file" ]; then
  echo "No Claude execution file at '${file}'; Claude probably did not start."
  [ -n "$notes" ] && echo "Claude did not start (no execution output); check the earlier steps of the run." > "$notes"
  exit 0
fi

# The file is a JSON array of messages; read JSON Lines too, in case the format changes.
lib='
def msgs: [ .[] | if type == "array" then .[] else . end ];
def clip($n): if length > $n then .[:$n] + " … (\(length - $n) more chars)" else . end;
def oneline: gsub("\\s*\n\\s*"; " ⏎ ");
def text_of: if type == "string" then . elif type == "array" then [ .[] | .text? // empty ] | join("\n") else tostring end;
def rel: if $ws != "" and startswith($ws + "/") then .[($ws | length) + 1:] else . end;
def c3: if length > 3 then (.[:-3] | c3) + "," + .[-3:] else . end;
def num: if . == null then "?" else round | tostring | c3 end;
def describe:
  .name as $n | (.input // {}) as $i
  | "\($n): " + (
      if $n == "Bash" then ($i.command // "")
      elif $i.file_path? then $i.file_path | rel
      elif $i.pattern? then $i.pattern + (if $i.path? then " in \($i.path | rel)" else "" end)
      else ($i | tojson) end
    | oneline | clip(400));
# "git commit -m ..." -> "git commit", "gh pr create ..." -> "gh pr create",
# "./gradlew lint --info" -> "./gradlew lint", "ls -la" -> "ls".
def cmdkey:
  [ (.input.command // "") | split(" ")[] | select(. != "") ] as $w
  | (if $w[0] == "gh" then 3 elif $w[0] == "git" or $w[0] == "./gradlew" then 2 else 1 end) as $k
  | $w[:$k] | join(" ");
def tally: group_by(.key) | map({ key: .[0].key, n: length,
    failed: map(select(.status == "failed")) | length,
    denied: map(select(.status == "denied")) | length }) | sort_by(-.n);
def tallytext: map("\(.key) ×\(.n)"
    + ([ (if .failed > 0 then "\(.failed) failed" else empty end),
         (if .denied > 0 then "\(.denied) denied" else empty end) ]
       | if length > 0 then " (" + join(", ") + ")" else "" end)) | join(" · ");

msgs as $m
| ($m | map(select(.type == "result")) | last // {}) as $r
| [ $r.permission_denials[]?.tool_use_id ] as $denied
| ([ $m[] | select(.type == "user") | .message.content | arrays | .[]
     | select(.type == "tool_result")
     | { key: .tool_use_id, value: { err: (.is_error == true), text: (.content | text_of) } } ]
   | from_entries) as $results
| [ $m[] | select(.type == "assistant") | .message.content[]? | select(.type == "text" or .type == "tool_use") ] as $blocks
| [ $blocks[] | select(.type == "tool_use") | . as $b | $results[$b.id] as $res
    | $b + { status: (if ($denied | any(. == $b.id)) then "denied"
                      elif $res == null then "no result"
                      elif $res.err then "failed" else "ok" end) } ] as $calls
# Token usage per model: the result message has it per model, or in total; failing both, add
# up the usage on each API response (several messages share one response id).
| ( [ ($r.modelUsage // {}) | to_entries[] | select(.value.inputTokens != null)
      | { model: .key, input: .value.inputTokens, output: .value.outputTokens,
          cache_read: (.value.cacheReadInputTokens // 0), cache_write: (.value.cacheCreationInputTokens // 0),
          cost: .value.costUSD } ] ) as $byModel
| ( if ($byModel | length) > 0 then $byModel
    elif $r.usage.input_tokens? != null then
      [ { model: "all models", input: $r.usage.input_tokens, output: $r.usage.output_tokens,
          cache_read: ($r.usage.cache_read_input_tokens // 0), cache_write: ($r.usage.cache_creation_input_tokens // 0),
          cost: $r.total_cost_usd } ]
    else
      [ $m[] | select(.type == "assistant") | .message | select(.usage != null) ]
      | unique_by(.id // tojson) | group_by(.model)
      | map({ model: (.[0].model // "unknown"), input: (map(.usage.input_tokens // 0) | add),
              output: (map(.usage.output_tokens // 0) | add),
              cache_read: (map(.usage.cache_read_input_tokens // 0) | add),
              cache_write: (map(.usage.cache_creation_input_tokens // 0) | add), cost: null })
    end ) as $tokens
| ( $tokens | { input: (map(.input) | add), output: (map(.output) | add),
                cache_read: (map(.cache_read) | add), cache_write: (map(.cache_write) | add) }
    | . + { total: (.input + .output + .cache_read + .cache_write) } ) as $tok
|
'

report() { jq -rs --arg ws "${GITHUB_WORKSPACE:-$PWD}" "$lib$1" "$file"; }

summary=$(report '
  "Outcome: \($r.subtype // "unknown (no result message)")\(if $r.is_error then " (error)" else "" end)"
  + " · turns: \($r.num_turns // "?")"
  + " · tool calls: \($calls | length)"
  + " · permission denials: \($denied | length)"
  + " · duration: \(($r.duration_ms // 0) / 60000 * 10 | round / 10) min"
  + " · cost: $\($r.total_cost_usd // 0 | . * 100 | round / 100)"
')

tokens_text=$(report '
  if ($tokens | length) == 0 then "Tokens: not recorded" else
    "Tokens: \($tok.total | num) total (input \($tok.input | num) · output \($tok.output | num)"
    + " · cache read \($tok.cache_read | num) · cache write \($tok.cache_write | num))",
    ($tokens[] | "  \(.model): input \(.input | num) · output \(.output | num)"
      + " · cache read \(.cache_read | num) · cache write \(.cache_write | num)"
      + (if .cost != null then " · $\(.cost * 100 | round / 100)" else "" end))
  end
')

tokens_md=$(report '
  if ($tokens | length) == 0 then "Token usage was not recorded." else
    "| Model | Input | Output | Cache read | Cache write | Cost |",
    "|---|--:|--:|--:|--:|--:|",
    ($tokens[] | "| \(.model) | \(.input | num) | \(.output | num) | \(.cache_read | num) | \(.cache_write | num) | "
      + (if .cost != null then "$\(.cost * 100 | round / 100)" else "" end) + " |"),
    (if ($tokens | length) > 1 then
      "| **Total** | \($tok.input | num) | \($tok.output | num) | \($tok.cache_read | num) | \($tok.cache_write | num) | $\($r.total_cost_usd // 0 | . * 100 | round / 100) |"
     else empty end),
    "",
    "\($tok.total | num) tokens in all; cache reads are the conversation re-read from the prompt cache each turn, billed at a fraction of input."
  end
')

activity=$(report '
  ($calls | map(select(.name == "Read")) | map(.input.file_path) | unique | length) as $read
  | ($calls | map(select(.name == "Glob" or .name == "Grep")) | length) as $searches
  | ($calls | map(select(.name == "Edit" or .name == "Write" or .name == "MultiEdit" or .name == "NotebookEdit")
              | select(.status == "ok") | (.input.file_path // .input.notebook_path // "?") | rel) | unique) as $changed
  | ($calls | map(select(.name == "Bash") | { key: cmdkey, status }) | tally) as $cmds
  | ($calls | map(select(.name | IN("Read", "Glob", "Grep", "Edit", "Write", "MultiEdit", "NotebookEdit", "Bash") | not)
              | { key: .name, status }) | tally) as $other
  | "Read \($read) file\(if $read == 1 then "" else "s" end) · \($searches) search\(if $searches == 1 then "" else "es" end) (Glob/Grep)",
    (if ($changed | length) == 0 then "Changed no files"
     else "Changed \($changed | length) file\(if ($changed | length) == 1 then "" else "s" end): "
       + ($changed[:15] | join(", ")) + (if ($changed | length) > 15 then ", …" else "" end) end),
    (if ($cmds | length) > 0 then "Commands: " + ($cmds | tallytext) else "Ran no commands" end),
    (if ($other | length) > 0 then "Other tools: " + ($other | tallytext) else empty end)
')

denials=$(report '
  $r.permission_denials[]? | "\(.tool_name): " + (
    if .tool_name == "Bash" then (.tool_input.command // "")
    elif .tool_input.file_path? then .tool_input.file_path | rel
    else (.tool_input | tojson) end
    | oneline | clip(400))
')

final=$(report '
  ($r.result // ([ $blocks[] | select(.type == "text") ] | last | .text?) // "") | clip(4000)
')

transcript=$(report '
  foreach $blocks[] as $b (0; if $b.type == "tool_use" then . + 1 else . end;
    if $b.type == "text" then
      "  Claude: " + ($b.text | clip(800) | gsub("\n"; "\n          "))
    else
      $results[$b.id] as $res
      | "#\(.) " + ($b | describe)
        + (if ($denied | any(. == $b.id)) then "\n     ✗ DENIED: not allowed by --allowedTools"
           elif $res.err then "\n     ✗ error: " + ($res.text | oneline | clip(600))
           elif $res == null then "\n     (no result; the session ended here)"
           else "" end)
    end)
')

{
  echo "=== Claude run report ==="
  echo "$summary"
  echo "$tokens_text"
  echo
  echo "--- What Claude did ---"
  echo "$activity"
  echo
  if [ -n "$denials" ]; then
    echo "--- Permission denials (commands Claude tried that --allowedTools blocks) ---"
    echo "$denials"
    echo
  fi
  echo "--- Claude's last message ---"
  echo "${final:-(none)}"
  echo
  echo "--- Transcript (tool calls in order; failed calls show their error) ---"
  echo "${transcript:-(no tool calls)}"
}

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "## Claude run report"
    echo
    echo "$summary"
    echo
    echo "### What Claude did"
    echo
    printf '%s\n' "$activity" | sed 's/^/- /'
    echo
    echo "### Tokens"
    echo
    echo "$tokens_md"
    echo
    if [ -n "$denials" ]; then
      echo "### Permission denials"
      echo
      echo "Commands Claude tried that \`--allowedTools\` blocks:"
      echo
      echo '````text'
      echo "$denials"
      echo '````'
      echo
    fi
    echo "### Claude's last message"
    echo
    echo "${final:-_(none)_}"
    echo
    echo "<details><summary>Transcript</summary>"
    echo
    echo '````text'
    echo "${transcript:-(no tool calls)}"
    echo '````'
    echo
    echo "</details>"
  } >> "$GITHUB_STEP_SUMMARY"
fi

if [ -n "$notes" ]; then
  {
    echo "$summary"
    echo
    printf '%s\n' "$activity" | sed 's/^/- /'
    echo "- $(head -1 <<<"$tokens_text")"
    if [ -n "$denials" ]; then
      echo
      echo "**Blocked tool calls** (not in \`--allowedTools\`):"
      echo '````text'
      echo "$denials"
      echo '````'
    fi
    echo
    echo "**Claude's last message:**"
    echo
    if [ -n "$final" ]; then printf '%s\n' "$final" | sed 's/^/> /'; else echo "> (none)"; fi
  } > "$notes"
fi
