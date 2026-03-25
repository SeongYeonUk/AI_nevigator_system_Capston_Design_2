package com.rabbit.domain.chat.service;

import com.rabbit.domain.chat.dto.ChatResponse;
import com.rabbit.domain.user.entity.User;
import com.rabbit.domain.user.repository.UserRepository;
import com.rabbit.global.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        // 🚨 주의: 여기에 민교님의 진짜 OPENAI_API_KEY (sk-...)를 반드시 넣어주세요!
        ""
})
@Transactional // 테스트가 끝나면 DB에 넣었던 임시 데이터들을 깔끔하게 지워줍니다.
class ChatServiceTest {

    @Autowired
    private ChatService chatService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("운영체제 - 데드락 - 은행원 알고리즘 라우팅 완벽 검증")
    void os_deadlock_routing_test() throws Exception {
        // 1. 테스트 유저 및 토큰 세팅
        User testUser = User.builder()
                .loginId("os_tester")
                .password("encoded_password")
                .nickname("OS테스터")
                .build();
        userRepository.save(testUser);

        String token = "Bearer " + jwtTokenProvider.createToken(testUser.getLoginId());

        // 2. 방 생성
        Long roomId = chatService.createRoom(token, "운영체제 테스트 방");

        System.out.println("===== 🚀 운영체제 심화 시나리오 시작 =====");

        // Step 1: 대주제/소주제 입력 (Seed 노드 생성)
        System.out.println("\n[진행] 대주제/소주제 4개 입력 중...");
        ChatResponse step1 = chatService.ask(token, roomId, null, "대주제- 컴퓨터공학, 소주제- 운영체제, 자료구조, 컴퓨터구조, 컴파일러");
        Thread.sleep(1000); // DB 반영 대기

        // Step 2: "데드락이 뭐야?"
        // 🌟 기대결과: 4개의 소주제 중 '운영체제' 밑으로 들어가야 함!
        System.out.println("\n[질문 1] 데드락이 뭐야?");
        ChatResponse step2 = chatService.ask(token, roomId, step1.getNewNodeId(), "데드락이 뭐야?");
        System.out.println("-> 데드락의 부모 ID: " + step2.getResolvedParentId() + " (운영체제 노드여야 함)");
        Thread.sleep(1000);

        // Step 3: "발생조건은 뭐야?"
        // 🌟 기대결과: '데드락' 밑으로 들어가야 함!
        System.out.println("\n[질문 2] 발생조건은 뭐야?");
        ChatResponse step3 = chatService.ask(token, roomId, step2.getNewNodeId(), "발생조건은 뭐야?");
        System.out.println("-> 발생조건의 부모 ID: " + step3.getResolvedParentId() + " (데드락 ID: " + step2.getNewNodeId() + " 여야 함)");
        Thread.sleep(1000);

        // Step 4: "은행원 알고리즘은?"
        // 🚨 프론트엔드가 엉뚱하게 맨 처음 노드(step1)를 부모로 보냈다고 가정해봅시다.
        // 🌟 기대결과: 프론트가 보낸 가짜 부모 무시하고, '데드락' 밑으로 들어가야 함!
        System.out.println("\n[질문 3] 은행원 알고리즘은?");
        ChatResponse step4 = chatService.ask(token, roomId, step1.getNewNodeId(), "은행원 알고리즘은?");
        System.out.println("-> 은행원 알고리즘의 부모 ID: " + step4.getResolvedParentId() + " (데드락 ID: " + step2.getNewNodeId() + " 여야 함)");

        System.out.println("\n✅ 운영체제 시나리오 테스트 완료! 콘솔 로그의 '🔥 GPT 결승전 라인업'을 확인해 보세요!");
    }

    @Test
    @DisplayName("트리의 정의를 물었을 때 비선형 자료구조 밑으로 완벽히 라우팅되는지 검증")
    void routing_tree_definition_test() throws Exception {
        // given: 1. 테스트용 임시 유저 생성 및 저장 (민교님의 User 엔티티에 맞춤!)
        User testUser = User.builder()
                .loginId("test_user_999")
                .password("encoded_password") // 테스트용이므로 아무거나 넣어도 무방
                .nickname("테스트유저")
                .build();
        userRepository.save(testUser);

        // given: 2. 방금 만든 임시 유저의 정보로 '진짜 JWT 토큰' 발급
        String rawToken = jwtTokenProvider.createToken(testUser.getLoginId());
        String token = "Bearer " + rawToken;

        // given: 3. 방 생성
        Long roomId = chatService.createRoom(token, "자료구조 테스트 방");

        System.out.println("===== 🚀 테스트 시나리오 시작 =====");

        // step 1: 대주제/소주제 입력 (Seed 노드 생성)
        ChatResponse step1 = chatService.ask(token, roomId, null, "대주제- 컴퓨터공학, 소주제- 운영체제, 자료구조");

        // step 2: "자료구조 종류 알려줘"
        Long dsNodeId = step1.getNewNodeId();
        ChatResponse step2 = chatService.ask(token, roomId, dsNodeId, "자료구조 종류 알려줘");
        Thread.sleep(1000); // 비동기 작업과 DB 반영을 위해 1초씩 대기

        // step 3: "선형 자료구조에는 뭐가 있어?"
        ChatResponse step3 = chatService.ask(token, roomId, step2.getNewNodeId(), "선형 자료구조에는 뭐가 있어?");
        Thread.sleep(1000);

        // step 4: "스택이랑 큐 설명해줘"
        ChatResponse step4 = chatService.ask(token, roomId, step3.getNewNodeId(), "스택이랑 큐 설명해줘");
        Thread.sleep(1000);

        // step 5: "그럼 비선형 자료구조는?" (비선형 노드 생성!)
        ChatResponse step5 = chatService.ask(token, roomId, step3.getResolvedParentId(), "그럼 비선형 자료구조는?");
        Long nonLinearNodeId = step5.getNewNodeId(); // 🌟 정답이 될 비선형 자료구조 노드의 ID
        Thread.sleep(1000);

        System.out.println("===== 🎯 대망의 '트리' 질문 던지기 =====");

        // when: 드디어 문제의 "트리의 정의" 질문을 던짐
        // 사용자가 '스택과 큐' 노드를 보고 질문했다고 가정하고 부모 ID를 던집니다.
        ChatResponse finalStep = chatService.ask(token, roomId, step4.getNewNodeId(), "트리 구조의 정의는 뭐야?");

        // then: 백엔드 GPT 심판이 '스택'을 버리고 '비선형(nonLinearNodeId)'을 제대로 찾아갔는지 검증!!
        System.out.println("기대하는 정답(비선형) ID: " + nonLinearNodeId);
        System.out.println("GPT가 찾은 부모 ID: " + finalStep.getResolvedParentId());

        assertThat(finalStep.getResolvedParentId()).isEqualTo(nonLinearNodeId);

        System.out.println("✅ 테스트 통과! 트리가 비선형 자료구조 밑에 완벽히 안착했습니다!");
    }

    @Test
    @DisplayName("선형/비선형 하위의 다양한 자료구조들이 자기 자리를 완벽히 찾아가는지 대규모 검증")
    void advanced_routing_scenario_test() throws Exception {
        // given: 1. 테스트 유저 세팅
        User testUser = User.builder()
                .loginId("advanced_tester")
                .password("encoded_password")
                .nickname("심화테스터")
                .build();
        userRepository.save(testUser);

        String rawToken = jwtTokenProvider.createToken(testUser.getLoginId());
        String token = "Bearer " + rawToken;

        // given: 2. 방 생성 및 기본 뼈대 세팅
        Long roomId = chatService.createRoom(token, "자료구조 심화 테스트 방");
        System.out.println("===== 🚀 심화 시나리오 뼈대 구축 시작 =====");

        ChatResponse step1 = chatService.ask(token, roomId, null, "대주제- 컴퓨터공학, 소주제- 운영체제, 자료구조");
        ChatResponse step2 = chatService.ask(token, roomId, step1.getNewNodeId(), "자료구조 종류 알려줘");
        Thread.sleep(1000);

        // 선형/비선형 기둥 세우기
        ChatResponse linearStep = chatService.ask(token, roomId, step2.getNewNodeId(), "선형 자료구조에는 뭐가 있어?");
        Long linearId = linearStep.getNewNodeId(); // 🌟 선형 부모 ID 확보
        Thread.sleep(1000);

        ChatResponse nonLinearStep = chatService.ask(token, roomId, step2.getNewNodeId(), "그럼 비선형 자료구조는?");
        Long nonLinearId = nonLinearStep.getNewNodeId(); // 🌟 비선형 부모 ID 확보
        Thread.sleep(1000);

        System.out.println("===== 🎯 심화 라우팅 테스트 시작 =====");

        // Test 1: 트리 구조 (기대: 비선형의 자식)
        // 사용자가 선형 노드(linearId)를 쳐다보고 질문했다고 꼬아봅니다.
        System.out.println("\n[질문 1] 트리 구조 알려줘");
        ChatResponse treeStep = chatService.ask(token, roomId, linearId, "트리 구조의 개념과 특징이 뭐야?");
        System.out.println("-> 기대(비선형): " + nonLinearId + " / 실제: " + treeStep.getResolvedParentId());
        assertThat(treeStep.getResolvedParentId()).isEqualTo(nonLinearId);
        Thread.sleep(1000);

        // Test 2: 그래프 구조 (기대: 비선형의 자식)
        // 사용자가 트리 노드(treeStep)를 쳐다보고 질문했다고 가정
        System.out.println("\n[질문 2] 그래프 구조 알려줘");
        ChatResponse graphStep = chatService.ask(token, roomId, treeStep.getNewNodeId(), "그래프 구조는 트상이랑 뭐가 달라?");
        System.out.println("-> 기대(비선형): " + nonLinearId + " / 실제: " + graphStep.getResolvedParentId());
        assertThat(graphStep.getResolvedParentId()).isEqualTo(nonLinearId); // 형제(Sibling)로 판정되어야 함!
        Thread.sleep(1000);

        // Test 3: 가중 그래프 (기대: 그래프의 자식)
        // 사용자가 비선형 노드(nonLinearId)를 쳐다보고 질문
        System.out.println("\n[질문 3] 가중 그래프 알려줘");
        ChatResponse weightedGraphStep = chatService.ask(token, roomId, nonLinearId, "가중 그래프는 언제 써?");
        System.out.println("-> 기대(그래프): " + graphStep.getNewNodeId() + " / 실제: " + weightedGraphStep.getResolvedParentId());
        assertThat(weightedGraphStep.getResolvedParentId()).isEqualTo(graphStep.getNewNodeId()); // 그래프의 자식으로 들어가야 함!
        Thread.sleep(1000);

        // Test 4: 연결 리스트 (기대: 선형의 자식)
        // 엄청난 점프! 방금까지 가중 그래프 얘기하다가 갑자기 연결리스트 질문
        System.out.println("\n[질문 4] 연결 리스트 알려줘");
        ChatResponse linkedListStep = chatService.ask(token, roomId, weightedGraphStep.getNewNodeId(), "아, 근데 연결 리스트는 어떻게 동작해?");
        System.out.println("-> 기대(선형): " + linearId + " / 실제: " + linkedListStep.getResolvedParentId());
        assertThat(linkedListStep.getResolvedParentId()).isEqualTo(linearId); // 선형 자료구조 밑으로 큰 점프!

        System.out.println("\n✅ 심화 시나리오 대성공! 래빗홀 가드 백엔드 트리 라우팅 완벽 검증 완료!");
    }
}
