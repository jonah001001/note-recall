from langgraph.graph.message import add_messages
from typing import Annotated
from typing_extensions import TypedDict
import os

from dotenv import load_dotenv
from langchain_core.prompts import ChatPromptTemplate

from langgraph.graph import StateGraph, START, END
from langgraph.checkpoint.memory import MemorySaver

BASE_DIR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

load_dotenv(os.path.join(BASE_DIR, ".env"))

class State(TypedDict):
    messages: Annotated[list, add_messages]
    question: str
    docs: list
    answer: str

RELEVANCE_THRESHOLD = 0.35


PROMPT = ChatPromptTemplate.from_messages([(
    "system",
    "你是一个用户笔记阅读助手，需要根据用户的笔记以及用户提供的【上下文】准确的找出跟用户问题相关的内容，"
    "遇到笔记或者上下文没有的内容，请诚实的说笔记上没有此问题的相关内容，不允许从外部或你自身的知识来解答。请以清晰的自然语言回答用户。"
), (
    "human",
    "【上下文】: \n{context}\n\n【问题】:\n{question}"
)])


def format_docs(docs):
    return "\n\n".join(d.page_content for d in docs)


def build_app(client, store):
    def retrieve(state: State) -> dict:
        hits = store.similarity_search_with_score(state["question"], k=6)
        docs = [d for d, score in hits if score >= RELEVANCE_THRESHOLD]
        return {"docs": docs}

    def generate(state: State) -> dict:
        context = format_docs(state["docs"])
        msg = PROMPT.invoke({"context": context, "question": state["question"]})
        ans = client.invoke(msg).content
        return {"answer": ans, "messages": [("assistant", ans)]}

    def refuse(state: State) -> dict:
        ans = "笔记中没有此问题相关的内容。"
        return {"answer": ans, "messages": [('assistant', ans)]}

    def rewrite(state: State) -> dict:
        history = state["messages"][:-1]
        if not history:
            return {"question": state["messages"][-1].content}
        history = "\n".join(f"{m.type}:{m.content}" for m in history)
        prompt = f"根据对话历史，把最后一个问题改写成独立完整的问题，保持原语言，只输出问题: \n{history}\n最后的问题: {state['messages'][-1].content}"
        return {"question": client.invoke(prompt).content}

    def route(state: State) -> str:
        return "generate" if state["docs"] else "refuse"


    g = StateGraph(State)
    g.add_node("rewrite", rewrite)
    g.add_node("retrieve", retrieve)
    g.add_node("generate", generate)
    g.add_node("refuse", refuse)
    g.add_edge(START, "rewrite")
    g.add_edge("rewrite", "retrieve")
    g.add_conditional_edges("retrieve", route, {"generate": "generate", "refuse": "refuse"})
    g.add_edge("generate", END)
    g.add_edge("refuse", END)
    app = g.compile(checkpointer=MemorySaver())
    # image = app.get_graph().draw_mermaid()
    return app

def chat(app, user_input: str) -> str:
    cfg = {"configurable": {"thread_id": "s1"}}
    resp = app.invoke({"messages": [('user', user_input)]}, cfg)
    return resp["answer"]