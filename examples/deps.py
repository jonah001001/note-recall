from langchain_ollama import OllamaEmbeddings
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_openai import ChatOpenAI
from functools import lru_cache
from dotenv import load_dotenv

import os

BASE_DIR =os.path.dirname(os.path.abspath(__file__))
env_path = os.path.join(BASE_DIR, ".env")
load_dotenv(env_path)

BASE_URL = os.getenv("DEEPSEEK_BASE_URL", "")

STORE_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "store.json")

def get_api_key():
    api_key = os.getenv("DEEPSEEK_API_KEY", "")
    return api_key

@lru_cache(maxsize=1)
def get_client() -> ChatOpenAI:
    return ChatOpenAI(model="deepseek-v4-flash", api_key=get_api_key, base_url=BASE_URL)


@lru_cache(maxsize=1)
def get_store() -> InMemoryVectorStore:
    return InMemoryVectorStore.load(STORE_PATH, embedding=OllamaEmbeddings(model="bge-m3"))