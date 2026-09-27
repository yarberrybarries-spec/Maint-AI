package ai.weixiu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName(value = "task_step_revision", autoResultMap = true)
public class TaskStepRevision implements Serializable {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Long requesterId;
    private String status;
    private String requestText;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object recognizedScope;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object agentResult;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object beforeSnapshot;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object afterSnapshot;
    private String resultSummary;
    private String errorMessage;
    private LocalDateTime expiresAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime appliedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
