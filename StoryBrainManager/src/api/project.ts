import { invoke } from "@tauri-apps/api/core";
import type { Project } from "@/types";

export async function listProjects(): Promise<Project[]> {
  return invoke("list_projects");
}

export async function createProject(name: string, novelText: string): Promise<Project> {
  return invoke("create_project", { name, novelText });
}

export async function saveProject(project: Project): Promise<void> {
  return invoke("save_project", { project });
}
