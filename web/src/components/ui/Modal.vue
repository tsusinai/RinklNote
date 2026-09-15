<script setup lang="ts">
import { toRef } from 'vue'
import Icon from './Icon.vue'
import { useOverlay } from './useOverlay'

/* 居中弹窗基础件（规格：Teleport + Esc 关闭 + 遮罩点击关闭 + Tab 焦点圈禁 + 浮层串行）
 * 面板进出场：fade + scale .96→1 / →1.02，220ms（样式见 overlay.css 的 ui-pop）。
 * v-model:open 或监听 close 均可关闭。 */
const props = withDefaults(defineProps<{
  open: boolean
  title?: string
  maxWidth?: string   // 面板最大宽度，如 '480px'；不传走 overlay.css 默认
  hideClose?: boolean // 隐藏右上角关闭按钮（如确认框自带双键时）
}>(), { title: '', hideClose: false })

const emit = defineEmits<{ (e: 'close'): void; (e: 'update:open', v: boolean): void }>()

function close(): void {
  emit('close')
  emit('update:open', false)
}

const { shown, panelRef, onAfterLeave } = useOverlay(toRef(props, 'open'), close)
</script>

<template>
  <Teleport to="body">
    <Transition name="ui-pop" @after-leave="onAfterLeave">
      <div v-if="shown" class="ui-overlay" @click.self="close">
        <div
          ref="panelRef"
          class="ui-panel"
          role="dialog" aria-modal="true" :aria-label="title || '对话框'"
          tabindex="-1"
          :style="maxWidth ? { maxWidth } : undefined"
        >
          <div v-if="title || !hideClose" class="ui-panel-head">
            <h3 v-if="title">{{ title }}</h3>
            <span v-else />
            <button v-if="!hideClose" class="ui-x" type="button" aria-label="关闭" @click="close">
              <Icon name="close" :size="18" />
            </button>
          </div>
          <slot />
        </div>
      </div>
    </Transition>
  </Teleport>
</template>
