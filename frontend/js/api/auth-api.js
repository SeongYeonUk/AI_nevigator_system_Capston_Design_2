import { API_BASE_URL } from "../config.js";

function normalizeErrorMessage(payload, status) {
  if (typeof payload === "string" && payload.trim()) {
    return payload.trim();
  }

  if (payload && typeof payload === "object") {
    if (typeof payload.message === "string" && payload.message.trim()) {
      return payload.message.trim();
    }
    if (typeof payload.error === "string" && payload.error.trim()) {
      return payload.error.trim();
    }
  }

  if (status >= 500) {
    return "서버 오류가 발생했습니다. 백엔드 실행 상태를 확인해 주세요.";
  }
  return "요청 처리에 실패했습니다.";
}

async function postJson(path, body) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(body)
  });

  const contentType = response.headers.get("content-type") || "";
  const isJson = contentType.includes("application/json");
  const payload = isJson ? await response.json().catch(() => null) : await response.text().catch(() => "");

  if (!response.ok) {
    throw new Error(normalizeErrorMessage(payload, response.status));
  }

  return payload;
}

export async function signupApi({ loginId, password, nickname }) {
  return postJson("/api/auth/signup", { loginId, password, nickname });
}

export async function loginApi({ loginId, password }) {
  const payload = await postJson("/api/auth/login", { loginId, password });
  if (!payload || typeof payload.accessToken !== "string" || !payload.accessToken) {
    throw new Error("로그인 응답에 토큰이 없습니다.");
  }
  return payload;
}
