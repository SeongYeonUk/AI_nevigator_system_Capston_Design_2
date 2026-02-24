import { API_BASE_URL } from "../config.js";

async function request(path, options, token) {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
      ...(options.headers || {})
    }
  });

  const contentType = response.headers.get("content-type") || "";
  const payload = contentType.includes("application/json")
    ? await response.json().catch(() => null)
    : await response.text().catch(() => "");

  if (!response.ok) {
    const msg =
      (payload && typeof payload === "object" && payload.message) ||
      (typeof payload === "string" && payload) ||
      "요청 처리에 실패했습니다.";
    throw new Error(msg);
  }
  return payload;
}

export function getProfileApi(token) {
  return request("/api/auth/profile", { method: "GET" }, token);
}

export function updateProfileApi(token, body) {
  return request(
    "/api/auth/profile",
    {
      method: "PUT",
      body: JSON.stringify(body)
    },
    token
  );
}

export function deleteAccountApi(token, password) {
  return request(
    "/api/auth/account",
    {
      method: "DELETE",
      body: JSON.stringify({ password })
    },
    token
  );
}
