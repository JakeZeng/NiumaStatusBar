import { create } from 'zustand';
import { invoke } from '@tauri-apps/api/core';
import { listen, type UnlistenFn } from '@tauri-apps/api/event';
import type { ProviderConfig, UsageStatus } from '../api';

interface StatusState {
  /** provider.id -> 最新 status（轮询推送 + 手动刷新） */
  byId: Record<string, UsageStatus>;
  /** provider.id -> 是否正在拉取中（用于按钮 spinner） */
  loadingById: Record<string, boolean>;

  /** 初始化：注册全局 status-update 监听 + 拉一次当前所有启用 provider 的状态 */
  init: () => Promise<() => void>;

  /** 全量更新 providers 列表（删/加 Provider 后调用，保证 stale 状态被回收） */
  setProviders: (list: ProviderConfig[]) => void;

  /** 手动触发一次 fetch，按 id 写入 loading；完成后由 status-update 事件回灌 */
  fetchOne: (id: string) => Promise<void>;
}

export const useStatusStore = create<StatusState>((set) => {
  // 单例 listener 句柄，App 卸载时 unlisten
  let unlistenStatus: UnlistenFn | null = null;

  return {
    byId: {},
    loadingById: {},

    init: async () => {
      if (unlistenStatus) return () => unlistenStatus?.();

      unlistenStatus = await listen<UsageStatus>('status-update', (event) => {
        const s = event.payload;
        if (!s || !s.provider_id) return;
        set((state) => ({
          byId: { ...state.byId, [s.provider_id]: s },
          // 后端推送即代表该 provider 已结束本轮 fetch
          loadingById: { ...state.loadingById, [s.provider_id]: false },
        }));
      });

      return () => {
        unlistenStatus?.();
        unlistenStatus = null;
      };
    },

    setProviders: (list) => {
      // 移除已删除 provider 的 status，避免内存泄漏
      set((state) => {
        const keep: Record<string, UsageStatus> = {};
        const keepLoading: Record<string, boolean> = {};
        const validIds = new Set(list.map((p) => p.id));
        for (const id of Object.keys(state.byId)) {
          if (validIds.has(id)) keep[id] = state.byId[id];
        }
        for (const id of Object.keys(state.loadingById)) {
          if (validIds.has(id)) keepLoading[id] = state.loadingById[id];
        }
        return { byId: keep, loadingById: keepLoading };
      });
    },

    fetchOne: async (id) => {
      set((state) => ({ loadingById: { ...state.loadingById, [id]: true } }));
      try {
        // 后端 fetch_provider_status 完成后会 emit status-update，由 listener 写入 byId
        // 失败时也走 emit（带 last_error），无需 catch
        await invoke<UsageStatus>('fetch_provider_status', { id });
      } catch (err) {
        // 异常路径：清掉 loading 状态（错误不会 emit 成功的 status）
        console.error('fetch_provider_status failed:', err);
        set((state) => ({ loadingById: { ...state.loadingById, [id]: false } }));
        throw err;
      }
    },
  };
});
