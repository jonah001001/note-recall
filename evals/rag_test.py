import json
from uuid import uuid4
import requests

base_url = "http://localhost:8080/chat/talk"

def read_cases():
    with open("questions.json", "r", encoding="utf-8") as f:
        data = json.load(f)
        return data


def call_rag(question: str) -> tuple[str, list[str]]:
    """请求rag接口，找到大模型返回的结果"""
    resp = requests.post(url=base_url, json={"sessionId": uuid4().hex.replace("-", ""), "userInput": question})
    resp.raise_for_status()
    response = resp.json()
    chat_resp = response.get("data", {})
    ai_resp = chat_resp.get("content", "")
    doc_source = chat_resp.get("citations", [])
    return ai_resp, doc_source


def eval_retrieval(expected_sources: list[str], actual_sources: list[str]) -> float:
    """大模型输出的来源文档占期望的来源文档的比例"""
    if not expected_sources:
        return 0.0
    source_files = {value.lower() for s in actual_sources for value in s.values()}
    hit = sum(1 for s in expected_sources if any(s.lower() in a for a in source_files))
    return hit / len(expected_sources)


def eval_answer(expected_points: list[str], actual_answer: str) -> float:
    """大模型回答的正确率"""
    if not expected_points:
        return 0.0
    answer_lower = actual_answer.lower().replace(" ", "")
    covered = sum(1 for p in expected_points if p.lower() in answer_lower)
    return covered / len(expected_points)


def eval_refusal(answer: str) -> float:
    refuse_signals = ["超出", "无法回答", "没有找到", "没有相关", "无法依据", "未提及", "没有记录"]
    refused = any(sig.lower() in answer for sig in refuse_signals)
    return 1.0 if refused else 0.0


def run():
    retrieval_scores = []
    answer_scores = []
    details = []

    cases = read_cases()
    for case in cases:
        answer, sources = call_rag(case["question"])
        if case.get("refuse"):
            r_score = a_score = score = eval_refusal(answer)
            retrieval_scores.append(score)
            answer_scores.append(score)
        else:
            r_score = eval_retrieval(case["expected_sources"], sources)
            a_score = eval_answer(case["expected_points"], answer)
            retrieval_scores.append(r_score)
            answer_scores.append(a_score)

        details.append({
            "id": case["id"],
            "question": case["question"],
            "retrieval_score": round(r_score, 2),
            "answer_score": round(a_score, 2),
            "actual_sources": sources,
            "answer": answer[:100] + "..." if len(answer) > 100 else answer
        })

    overall_retrieval = sum(retrieval_scores) / len(retrieval_scores)
    overall_answer = sum(answer_scores) / len(answer_scores)

    print("*"*100)
    print(f"检索准确率（整体）：{overall_retrieval:.2%}")
    print(f"回答正确率（整体）：{overall_answer:.2%}")
    print("*"*100)

    print("\n【得分较低的用例】")
    for d in sorted(details, key=lambda x: x["retrieval_score"]+x["answer_score"]):
        if d["retrieval_score"] < 1.0 or d["answer_score"] < 0.6:
            print(f"  [{d['id']}]检索{d['retrieval_score']} 回答{d['answer_score']} | {d['question']}")
            print(f"          实际来源：{d['actual_sources']}")

    return overall_retrieval, overall_answer, details


if __name__ == "__main__":
    run()

