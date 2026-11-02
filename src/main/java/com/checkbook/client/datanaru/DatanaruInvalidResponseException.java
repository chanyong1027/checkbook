package com.checkbook.client.datanaru;

/**
 * 정보나루가 200을 줬지만 소장·대출 값이 비었거나 Y/N이 아닌 경우. 미소장(false)으로 바꾸지 않고 실패로 다룬다 —
 * 미소장으로 두면 24시간 스냅샷에 저장돼 하루 동안 "없음"으로 보인다.
 * 부모({@link DatanaruResponseException})처럼 서킷은 실패로 세지 않는다(응답은 온 것이라).
 */
public class DatanaruInvalidResponseException extends DatanaruResponseException {
    public DatanaruInvalidResponseException(String message) {
        super(message);
    }
}
