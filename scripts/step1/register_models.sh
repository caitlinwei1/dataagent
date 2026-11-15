#!/usr/bin/env bash
# Register DashScope (OpenAI-compatible) chat + embedding models via DataAgent's model registry API.
set -euo pipefail
: "${DASHSCOPE_API_KEY:?set DASHSCOPE_API_KEY}"
API=${API:-http://localhost:8065/api/model-config}
CHAT_MODEL=${CHAT_MODEL:-qwen3.8-flash}
EMBED_MODEL=${EMBED_MODEL:-qwen3.7-text-embedding-flash}
BASE=https://dashscope.aliyuncs.com/compatible-mode

add() { curl -sf -X POST "$API/add" -H 'Content-Type: application/json' -d "$1"; echo; }
add "{\"provider\":\"dashscope\",\"baseUrl\":\"$BASE\",\"apiKey\":\"$DASHSCOPE_API_KEY\",\"modelName\":\"$CHAT_MODEL\",\"modelType\":\"CHAT\",\"temperature\":0,\"maxTokens\":4000,\"completionsPath\":\"/v1/chat/completions\"}"
add "{\"provider\":\"dashscope\",\"baseUrl\":\"$BASE\",\"apiKey\":\"$DASHSCOPE_API_KEY\",\"modelName\":\"$EMBED_MODEL\",\"modelType\":\"EMBEDDING\",\"embeddingsPath\":\"/v1/embeddings\"}"
curl -s "$API/list"; echo
echo "Activate each id with: curl -X POST $API/activate/<id>"
