# 步骤 17：Home页工程列表与创建流程联调

## 目标
在 Home 页加入"新建工程"入口（表单：工程名 + 粘贴/导入小说文本），调用步骤 16 的
`create_project`，创建成功后自动跳转到该工程的 `ProjectWorkspace` 页面。

## 涉及文件
- `src/pages/Home/index.tsx`（加新建按钮 + 弹窗/内联表单）
- `src/api/project.ts`（已有 `createProject` 封装，直接复用）

## 实现要点
```tsx
const navigate = useNavigate();
const [showForm, setShowForm] = useState(false);
const [name, setName] = useState("");
const [text, setText] = useState("");

const handleCreate = async () => {
  const project = await createProject(name, text);
  navigate(`/project/${project.id}`);
};
```
`ProjectWorkspace` 需要根据路由参数 `projectId` 去 `listProjects` 中查找对应工程，
把 `novelText` 回填到步骤 14 的文本框里（而不是每次都要重新粘贴）。

## 验证方法
1. Home 页点击"新建工程"
2. 输入工程名（如"红楼梦片段测试"）和一段小说文本
3. 点击确认，应自动跳转到 `/project/<新工程id>`
4. 确认 ProjectWorkspace 页面里的文本框已经自动填充了刚才输入的小说文本（无需
   重新粘贴）
5. 返回 Home 页（浏览器后退或导航），确认新工程出现在列表中，无需手动刷新页面

## 完成标准 (DoD)
- [ ] 新建流程走完后自动跳转，无需手动导航
- [ ] Workspace 页正确回填 `novelText`，无需重复输入
- [ ] Home 列表能实时反映新建的工程（不刷新页面也能看到）
