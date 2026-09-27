package ai.weixiu.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
@TableName(value = "task_auto_review", autoResultMap = true)
public class TaskAutoReview {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;
    private Long taskId;
    private Integer evidenceVersion;
    private String requestId;
    private String status;
    private String evidenceStatus;
    private Integer evidenceScore;
    private Integer totalScore;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object dimensionScores;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object agentResult;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object decisionReasons;
    private String ruleVersion;
    private String modelName;
    private String modelRequestId;
    private String errorMessage;
    private Long reviewedBy;
    private String reviewComment;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
