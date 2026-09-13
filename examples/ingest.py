from langchain_ollama import OllamaEmbeddings
from pathlib import Path
from langchain_core.documents import Document
from langchain_core.vectorstores import InMemoryVectorStore
from langchain_text_splitters import MarkdownHeaderTextSplitter, RecursiveCharacterTextSplitter


def new_store() -> InMemoryVectorStore:
    return InMemoryVectorStore(embedding=OllamaEmbeddings(model="bge-m3"))


def load_notes(notes_dir: Path) -> list[Document]:
    by_header = MarkdownHeaderTextSplitter([("#", "h1"), ("##", "h2")])  
    by_size = RecursiveCharacterTextSplitter(                              
        chunk_size=500, chunk_overlap=50,
        separators=["\n\n", "\n", "。", "！", "？", "；", " ", ""]) 
    docs = []
    for path in notes_dir.glob("*.md"):
        for section in by_header.split_text(path.read_text(encoding="utf-8")):
            title = section.metadata.get("h2") or section.metadata.get("h1", "")
            for chunk in by_size.split_text(section.page_content):
                docs.append(Document(
                    page_content=f"【{path.stem} - {title}】{chunk}",
                    metadata={"filename": path.name, "title": title}))
    return docs


def embedding_docs(store: InMemoryVectorStore, docs: list[Document]) -> InMemoryVectorStore:
    store.add_documents(docs) 
    return store


def save_embeddings(path: str, store: InMemoryVectorStore):      
    store.dump(path)