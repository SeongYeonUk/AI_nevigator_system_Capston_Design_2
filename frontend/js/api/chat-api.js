import { API_BASE_URL, CHAT_API_MODE } from "../config.js";

function buildMockAnswer(question, depth) {
  const snippets = [
    "핵심 목표를 먼저 분해하고 우선순위를 정리해 보세요.",
    "이전 맥락과 연결되는 하위 주제로 분기하면 학습 효율이 높아집니다.",
    "다음 단계에서 검증 가능한 작은 작업 단위를 정의하는 것이 좋습니다.",
    "비슷한 질문을 묶어 트리 단위로 비교하면 빠르게 정리할 수 있습니다."
  ];
  const idx = (question.length + depth) % snippets.length;
  return snippets[idx];
}

async function callBackendChat({ message, parentNodeId, token }) {
  const response = await fetch(`${API_BASE_URL}/api/chat/message`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify({ message, parentNodeId })
  });

  if (!response.ok) {
    throw new Error("채팅 API 호출에 실패했습니다.");
  }

  return response.json();
}

export async function requestAssistantTurn({ message, depth, parentNodeId, token }) {
  if (CHAT_API_MODE === "backend") {
    return callBackendChat({ message, parentNodeId, token });
  }

  return {
    answer: buildMockAnswer(message, depth),
    source: "mock"
  };
}
