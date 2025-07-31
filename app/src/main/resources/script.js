document.addEventListener('DOMContentLoaded', () => {
    // 获取所有需要的HTML元素
    const topicInput = document.getElementById('topic-input');
    const roundsInput = document.getElementById('rounds-input');
    const startButton = document.getElementById('start-button');
    const outputContainer = document.getElementById('debate-output-container'); // 可滚动的父容器
    const outputDiv = document.getElementById('debate-output');       // 存放内容的div
    const statusIndicator = document.getElementById('status-indicator');

    let eventSource;

    /**
     * 将输出容器的滚动条滚动到底部
     */
    function scrollToBottom() {
        if (outputContainer) {
            outputContainer.scrollTop = outputContainer.scrollHeight;
        }
    }

    /**
     * 停止当前的辩论，关闭连接并重置UI
     * @param {string} message - 显示在状态栏的最终信息
     */
    function stopDebate(message = '辩论已结束！感谢您的参与。') {
        if (eventSource) {
            eventSource.close();
            eventSource = null;
        }
        statusIndicator.textContent = message;
        startButton.disabled = false;
        startButton.textContent = '开始新的辩论';
    }

    // 为开始按钮绑定点击事件
    startButton.addEventListener('click', () => {
        // 如果按钮是“停止辩论”，说明正在进行中，点击则停止
        if (startButton.textContent === '停止辩论') {
            stopDebate('辩论已由用户手动中止。');
            return;
        }

        const topic = topicInput.value.trim();
        const rounds = roundsInput.value;

        if (!topic) {
            alert('请输入辩论主题！');
            return;
        }

        // --- 准备UI界面 ---
        outputDiv.innerHTML = ''; // 清空上一场辩论的内容
        startButton.disabled = true;
        startButton.textContent = '停止辩论';
        statusIndicator.textContent = '正在连接服务器并准备辩论...';

        // --- 建立SSE连接 ---
        const url = `/debate?topic=${encodeURIComponent(topic)}&rounds=${encodeURIComponent(rounds)}`;
        eventSource = new EventSource(url);

        // 当连接成功建立时触发
        eventSource.onopen = () => {
            console.log("SSE connection established.");
            statusIndicator.textContent = '辩论进行中...';
        };

        // --- 为来自服务器的命名事件添加监听器 ---

        // 监听 'message' 事件 (包含辩论发言)
        eventSource.addEventListener('message', event => {
            const turnData = JSON.parse(event.data);
            const side = turnData.side;
            const model = turnData.model;
            const speech = turnData.speech;

            const messageElement = document.createElement('div');
            messageElement.classList.add('message', side === '正方' ? 'pro' : 'con');

            const modelBadge = `<span class="model-badge ${model.toLowerCase()}">${model}</span>`;
            const formattedSpeech = speech.replace(/\n/g, '<br>');

            messageElement.innerHTML = `
                <div class="message-header">${side} ${modelBadge}</div>
                <div class="message-content">${formattedSpeech}</div>
            `;
            outputDiv.appendChild(messageElement);
            scrollToBottom();
        });

        // 监听 'round_separator' 事件
        eventSource.addEventListener('round_separator', event => {
            const roundInfo = event.data;
            const separatorElement = document.createElement('div');
            separatorElement.classList.add('round-separator');
            separatorElement.textContent = roundInfo;
            outputDiv.appendChild(separatorElement);
            scrollToBottom();
        });

        // 监听 'verdict' 事件 (最终裁决)
        eventSource.addEventListener('verdict', event => {
            const verdictText = event.data
                .replace(/### (.*)/g, '<h3>$1</h3>')
                .replace(/#### (.*)/g, '<h4>$1</h4>')
                .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
                .replace(/\| /g, '')
                .replace(/\n/g, '<br>');

            const verdictElement = document.createElement('div');
            verdictElement.classList.add('verdict');
            verdictElement.innerHTML = verdictText;
            outputDiv.appendChild(verdictElement);
            scrollToBottom();
        });

        // 监听服务器发送的 'finish' 事件
        eventSource.addEventListener('finish', event => {
            console.log("Server finished the debate process:", event.data);
            stopDebate(); // 使用默认的结束信息
        });

        // 处理任何连接错误
        eventSource.onerror = (err) => {
            console.error("EventSource failed:", err);
            stopDebate('与服务器的连接已断开或发生错误。');
        };
    });
});