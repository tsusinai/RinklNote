/* ECharts 按需注册统一出口（Task 3.2 bundle 瘦身）
 * - 全站唯一注册点：按需引入 Line/Bar/Pie 三类图 + tooltip/legend/grid 组件 + Canvas 渲染器，
 *   严禁 `import 'echarts'` 全量包回归；后续新增图表类型 / 组件只改这里。
 * - 统一再导出 vue-echarts 的 VChart：图表组件一律 `import VChart from '../utils/echarts'`，
 *   echarts 相关代码由打包器收敛进共享 chunk，多页面引用不会重复打包。
 * - 注册与导出均为模块级副作用（幂等），首次 import 时完成。 */
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import VChart from 'vue-echarts'

use([CanvasRenderer, PieChart, LineChart, BarChart, GridComponent, TooltipComponent, LegendComponent])

export { use, VChart }
export default VChart
