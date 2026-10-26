package com.copenhagen.wallet

import android.annotation.SuppressLint
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** 푸시는 요청 종류와 ID만 전달한다. 상세 조회와 사용자 인증은 기존 앱 흐름에서 처리한다. */
@SuppressLint("MissingFirebaseInstanceTokenRefresh") // FID 모드: 토큰 대신 로그인/복원/재연결 때 현재 FID를 등록한다.
class ApprovalMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        ApprovalNotifications.receive(this, message.data)
    }
}
