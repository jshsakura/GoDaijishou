<div align="center">
  <img src="https://github.com/jshsakura/GoDaijishou/blob/master/app/sampledata/icon.jpg?raw=true" width="200" style="border-radius: 20px;"/>
</div>


**🧙‍♂️ Go Daijishou Xposed Module Project.**
<div align="center">
    <img src="https://github.com/jshsakura/GoDaijishou/blob/master/app/sampledata/preview1.JPEG?raw=true" style="border-radius: 5px;"/>
    <img src="https://github.com/jshsakura/GoDaijishou/blob/master/app/sampledata/preview2.JPEG?raw=true" style="border-radius: 5px;"/>
    <img src="https://github.com/jshsakura/GoDaijishou/blob/master/app/sampledata/preview3.JPEG?raw=true" style="border-radius: 5px;"/>
</div>
이 Xposed 모듈은 루팅된 LG Wing 스마트폰의 세컨드 런처 화면의 홈을 다이지쇼로 대체합니다.

윙의 두 번째 런처인 `com.lge.secondlauncher`의 홈 화면 동작을 가로채서 다이지쇼(Daijishou)로 기본 런처 처럼 실행하게 됩니다.

만약 다이지쇼가 설치되지 않은 경우라면 동작하지 않고 화면 아래에 설치 안내 메세지가 나타나게 됩니다.

This Xposed module is designed for rooted LG Wing smartphones.

Its purpose is to intercept and modify the behavior of the second launcher's home screen.

Instead of launching the default LG second launcher, it redirects the home action to open Daijishou, an alternative launcher application.

This module utilizes the Xposed framework to achieve this functionality, allowing for system-level modifications without altering the original system files.
