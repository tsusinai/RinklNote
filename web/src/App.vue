<script setup lang="ts">
import { RouterView } from 'vue-router'
import GlobalToast from './components/GlobalToast.vue'
</script>

<template>
  <RouterView />
  <GlobalToast />
</template>
