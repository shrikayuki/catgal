import { ref } from 'vue'
import { defineStore } from 'pinia'

export type NotificationTone = 'info' | 'success' | 'warning' | 'error'

export interface AppNotification {
  id: number
  message: string
  tone: NotificationTone
}

export const useNotificationStore = defineStore('notifications', () => {
  const items = ref<AppNotification[]>([])
  let nextId = 1

  function remove(id: number): void {
    items.value = items.value.filter((item) => item.id !== id)
  }

  function show(message: string, tone: NotificationTone = 'info', duration = 3600): number {
    const id = nextId++
    items.value.push({ id, message, tone })
    window.setTimeout(() => remove(id), duration)
    return id
  }

  return { items, show, remove }
})
