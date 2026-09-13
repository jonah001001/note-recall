from langchain_openai import ChatOpenAI
from langchain_mcp_adapters.client import MultiServerMCPClient
from langchain.agents import create_agent


SYSTEM = """你是用户的个人技术笔记助手。回答问题时必须先调用 searchNotes 检索笔记,
只依据检索到的笔记内容回答,并注明来源笔记。
如果笔记中没有相关内容,直接说明,不要用你自己的知识补充答案。
你可以在回答末尾用一句话指出笔记中缺失的相关知识点(只列标题,不展开内容),
方便用户决定是否补充。除非用户明确要求,不要主动调用 saveNote。"""


async def run_mcp(client: ChatOpenAI)-> str:
    mcp_client = MultiServerMCPClient({"note-recall": {"url": "http://localhost:8080/mcp", "transport": "streamable_http"}})
    tools = await mcp_client.get_tools()
    print(f"tools: {','.join([t.get_name() for t in tools])}")
    agent = create_agent(client, tools, system_prompt=SYSTEM)
    r = await agent.ainvoke({"messages": [("user", "查一下我笔记里Redis持久化怎么写的")]})
    return r["messages"][-1].content