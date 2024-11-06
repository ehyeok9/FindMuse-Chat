package com.whh.findmusechatting.chat.service;

import com.whh.findmusechatting.chat.dto.response.ChatRoomResponse;
import com.whh.findmusechatting.chat.dto.request.ChatRoomUpdateRequest;
import com.whh.findmusechatting.chat.entity.*;
import com.whh.findmusechatting.chat.entity.constant.MessageType;
import com.whh.findmusechatting.chat.entity.constant.NotificationType;
import com.whh.findmusechatting.chat.repository.ChatMessageRepository;
import com.whh.findmusechatting.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Description;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository messageRepository;
    private final ChatService chatService;

    private final KafkaTemplate<String, ChatNotification> notificationKafkaTemplate;
    private final Map<String, Sinks.Many<ChatMessage>> messagesSinks;

    @Value("${spring.kafka.topic.notification}")
    private String notificationTopic;

    @Description("채팅방 생성")
    public Mono<ChatRoom> createChatRoom(ChatRoom chatRoom) {
        chatRoom.setCreatedAt(LocalDateTime.now());
        return chatRoomRepository.save(chatRoom)
                .doOnSuccess(saved -> {
                    messagesSinks.computeIfAbsent(saved.getId(),
                            id -> Sinks.many().multicast().onBackpressureBuffer());
                })
                .doOnError(throwable -> {
                    log.error("채팅방 생성 중 에러가 발생했습니다. : {}", throwable.getMessage());
                });
    }

    @Description("채팅방 참여")
    public Mono<ChatRoom> joinChatRoom(String roomId, String userId) {
        return chatRoomRepository.findById(roomId)
                .flatMap(chatRoom -> {
                    // 이미 참여한 사용자인지 확인
                    if (chatRoom.getParticipants().contains(userId)) {
                        return Mono.error(new IllegalStateException("이미 참여한 채팅방입니다."));
                    }

                    // 참여자 목록에 추가
                    chatRoom.getParticipants().add(userId);

                    // 시스템 메시지 생성
                    ChatMessage systemMessage = ChatMessage.builder()
                            .roomId(roomId)
                            .senderId("SYSTEM")
                            .senderName("SYSTEM")
                            .content(userId + "님이 입장하셨습니다.")
                            .timestamp(LocalDateTime.now())
                            .messageType(MessageType.SYSTEM)
                            .build();

                    return chatRoomRepository.save(chatRoom)
                            .flatMap(savedRoom ->
                                    chatService.sendMessage(systemMessage)
                                            .thenReturn(savedRoom));
                });
    }

    @Description("채팅방 목록 조회")
    public Flux<ChatRoomResponse> getUserChatRooms(String userId) {
        return chatRoomRepository.findByParticipantsContaining(userId)
                .flatMap(chatRoom -> {
                    // 마지막 메시지 조회
                    Mono<ChatMessage> lastMessageMono = messageRepository
                            .findByRoomIdOrderByTimestampDesc(chatRoom.getId())
                            .next()
                            .defaultIfEmpty(ChatMessage.builder().build());

                    // 안 읽은 메시지 수 조회
                    Mono<Long> unreadCountMono = messageRepository
                            .countUnreadMessages(chatRoom.getId(), userId);

                    return Mono.zip(lastMessageMono, unreadCountMono)
                            .map(tuple -> {
                                ChatMessage lastMessage = tuple.getT1();
                                Long unreadCount = tuple.getT2();

                                return ChatRoomResponse.builder()
                                        .id(chatRoom.getId())
                                        .name(chatRoom.getName())
                                        .thumbnail(chatRoom.getThumbnail())
                                        .lastMessage(lastMessage.getContent())
                                        .lastMessageTime(ChatRoomResponse.formatLastMessageTime(lastMessage.getTimestamp()))
                                        .unreadCount(unreadCount.intValue())
                                        .participantCount(chatRoom.getParticipants().size())
                                        .participants(chatRoom.getParticipants())
                                        .build();
                            });
                });
    }

    @Description("채팅방 수정")
    public Mono<ChatRoom> updateChatRoom(String roomId, ChatRoomUpdateRequest request) {
        return chatRoomRepository.findById(roomId)
                .flatMap(chaRoom -> {
                    chaRoom.updateChatRoom(request);
                    return chatRoomRepository.save(chaRoom);
                });
    }

    @Description("채팅방 나가기")
    public Mono<ChatRoom> leaveChatRoom(String roomId, String userId) {
        return chatRoomRepository.findById(roomId)
                .flatMap(chatRoom -> {
                    // 참여자가 아닌 경우
                    if (!chatRoom.getParticipants().contains(userId)) {
                        return Mono.error(new IllegalStateException("참여하지 않은 채팅방입니다."));
                    }

                    // 참여자 목록에서 제거
                    chatRoom.getParticipants().remove(userId);

                    // 시스템 메시지 생성
                    ChatMessage systemMessage = ChatMessage.builder()
                            .roomId(roomId)
                            .senderId("SYSTEM")
                            .senderName("SYSTEM")
                            .content(userId + "님이 퇴장하셨습니다.")
                            .timestamp(LocalDateTime.now())
                            .messageType(MessageType.SYSTEM)
                            .build();

                    return chatRoomRepository.save(chatRoom)
                            .flatMap(savedRoom ->
                                    chatService.sendMessage(systemMessage)
                                            .thenReturn(savedRoom));
                });
    }

    @Description("채팅방 삭제")
    public Mono<Void> deleteChatRoom(String roomId, String userId) {
        return chatRoomRepository.findById(roomId)
                .flatMap(chatRoom -> {
                    // 방장이 아닌 경우
                    if (!chatRoom.getOwner().equals(userId)) {
                        return Mono.error(new IllegalStateException("방장만 채팅방을 삭제할 수 있습니다."));
                    }

                    // 채팅방 관련 모든 메시지 삭제
                    return messageRepository.deleteByRoomId(roomId)
                            .then(chatRoomRepository.delete(chatRoom))
                            .then(Mono.fromRunnable(() -> {
                                // Sink 제거
                                messagesSinks.remove(roomId);

                                // 참여자들의 알림 발송
                                ChatNotification deleteNotification = ChatNotification.builder()
                                        .type(NotificationType.ROOM_DELETED)
                                        .roomId(roomId)
                                        .content("채팅방이 삭제되었습니다.")
                                        .timestamp(LocalDateTime.now())
                                        .build();

                                chatRoom.getParticipants().forEach(participantId ->
                                        notificationKafkaTemplate.send(notificationTopic,
                                                participantId, deleteNotification));
                            }));
                });
    }
}
