from mcp_agent import run_mcp
from deps import get_store, get_client
from pathlib import Path
from langchain_core.vectorstores import InMemoryVectorStore
from graph import chat, build_app
from ingest import save_embeddings, load_notes, embedding_docs, new_store
import os
import asyncio

BASE_PATH = os.path.dirname(os.path.abspath(__file__))

def embedding(notes_dir: Path, save_path: str) -> InMemoryVectorStore:
    docs = load_notes(notes_dir=notes_dir)
    store = new_store()
    embedding_docs(store, docs)
    save_embeddings(save_path, store)
    print(f"笔记向量化完成, 存储路径：{save_path}")
    return store


def search(user_input: str, app) -> str:
    resp = chat(app, user_input)
    return resp


if __name__ == "__main__":
    note_dir = Path(os.path.join(BASE_PATH, "notes"))
    embedding_path = os.path.join(BASE_PATH, "store.json")
    store = embedding(note_dir, embedding_path)
    client= get_client()
    store = get_store()
    app = build_app(client, store)
    resp = search("redis持久化是什么？", app)
    print(f"reply: {resp}")

    mcp_reply = asyncio.run(run_mcp(client))
    print(f"mcp_reply: {mcp_reply}")