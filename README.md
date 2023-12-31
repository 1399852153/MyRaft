# Mit6.824分布式系统课程 raft实现
#####
raft的论文中将raft算法的功能分解为4个模块：  
1. leader选举
2. 日志复制
3. 快照和日志压缩
4. 集群成员动态变更
#####
* 其中前两项“leader选举”和“日志复制”是raft算法的基础，而后两项“日志压缩”和“集群成员动态变更”属于raft算法在功能上的拓展优化。  
* 由于实现“集群成员动态变更”会对原有逻辑进行较大改动而大幅增加复杂度，限于个人水平，本项目中将只实现前三项功能。  
* 按照计划会以3个迭代分支来完成，每个迭代都会以博客的形式进行讲解和分享，希望能帮助到对raft算法感兴趣的小伙伴。(更新中)

### 项目依赖
* 项目中依赖的rpc框架是上一个实验中实现的MyRpc，项目地址：https://github.com/1399852153/MyRpc (myrpc-core模块)

### 博客地址
* leader选举 博客地址：[手写raft(一) 实现leader选举](https://www.cnblogs.com/xiaoxiongcanguan/p/17569697.html)
* 日志复制    博客地址：[手写raft(二) 实现日志复制](https://www.cnblogs.com/xiaoxiongcanguan/p/17636220.html)
* 日志压缩    博客地址：[手写raft(三) 实现日志压缩](https://www.cnblogs.com/xiaoxiongcanguan/p/17670468.html)