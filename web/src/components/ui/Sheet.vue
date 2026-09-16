<script setup lang="ts">
import { toRef } from 'vue'
import Icon from './Icon.vue'
import { useOverlay } from './useOverlay'

/* 底部抽屉基础件（移动端优先的表单/操作承载）：
 * 与 Modal 同一套串行闸门/Esc/焦点圈禁，面板走 translateY 进出场（250ms，--dur-sheet，
 * 样式见 overlay.css 的 ui-sheet 与 .ui-overlay.sheet）。 */
const props = withDefaults(defineProps<{
  open: boolean
  title?: string
}>(), { title: '' })

const emit = defineEmits<{ (e: 'close'): void; (e: 'update:open', v: boolean): void }>()

function close(): void {
  emit('close')
  emit('update:open', false)
}

const { shown, panelRef, onAfterLeave } = useOverlay(toRef(props, 'open'), close)
</script>

<template>
  <Teleport to="body">
    <Transition name="ui-sheet" @after-leave="onAfterLeave">
      <div v-if="shown" class="ui-overlay sheet" @click.self="close">
        <div
          ref="panelRef"
          class="ui-panel"
          role="dialog" aria-modal="true" :aria-label="title || '面板'"
          tabindex="-1"
        >
          <span class="grabber" aria-hidden="true" />
          <!-- 抽屉始终保留头部：右上角关闭按钮是移动端主退出路径 -->
          <div class="ui-panel-head">
            <h3 v-if="title">{{ title }}</h3>
            <span v-else />
            <button class="ui-x" type="button" aria-label="关闭" @click="close">
              <Icon name="close" :size="18" />
            </button>
          </div>
          <slot />
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* 顶部拖拽指示条（纯装饰） */
.grabber {
  width: 36px; height: 4px; border-radius: 999px;
  background: var(--border); margin: -4px auto 2px;
}
</style>
